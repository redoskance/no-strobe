use crate::camera::CameraLimits;
use crate::detector::{fit_with_seed, pulse_period, PulseTracker};
use crate::signal::{Frame, SignalBuffer};
use crate::solver::{solve, Mode};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    /// Short exposure, collecting a flicker signal.
    Search,
    /// Strobe period known, exposure locked to it.
    Locked,
}

#[derive(Debug, Clone, Copy)]
pub struct Command {
    pub exposure_us: f64,
    pub fps: f64,
    pub iso: f64,
    pub mode: Mode,
    pub state: State,
    pub period_us: f64,
    pub confidence: f64,
    pub drift_us_per_frame: f64,
}

impl Default for Command {
    fn default() -> Self {
        Self {
            exposure_us: 0.0,
            fps: 0.0,
            iso: 0.0,
            mode: Mode::Flash,
            state: State::Search,
            period_us: 0.0,
            confidence: 0.0,
            drift_us_per_frame: 0.0,
        }
    }
}

/// Short exposure used while searching. A long exposure integrates whole strobe cycles and
/// flattens the signal, so detection needs a narrow sampling window.
const SEARCH_EXPOSURE_US: f64 = 8_000.0;
/// Share of the frame time the search exposure covers.
const SEARCH_FRAME_FRACTION: f64 = 0.9;

pub struct Controller {
    pub limits: CameraLimits,
    pub buf: SignalBuffer,
    pub tracker: Option<PulseTracker>,
    pub state: State,
    pub warmup_frames: usize,
    pub last_command: Command,
}

impl Controller {
    pub fn new(limits: CameraLimits) -> Self {
        let fps = limits
            .supported_fps
            .iter()
            .max_by(|a, b| a.partial_cmp(b).unwrap())
            .copied()
            .unwrap_or(30.0);
        let exposure = search_exposure(&limits, 1e6 / fps);
        Self {
            limits,
            buf: SignalBuffer::new(240),
            tracker: None,
            state: State::Search,
            warmup_frames: 45,
            last_command: Command {
                exposure_us: exposure,
                fps,
                ..Command::default()
            },
        }
    }

    pub fn on_frame(&mut self, t_us: f64, luminance: f64) -> Command {
        self.buf.push(Frame { t_us, luminance });
        if self.state == State::Search {
            self.search()
        } else {
            self.track()
        }
    }

    /// Times of the pulses of the buffer: the luma minus a LOCAL baseline (a centred running median of about
    /// two seconds), then edges with hysteresis.
    ///
    /// - The local baseline follows a slow ramp (stage lights fading, the auto-exposure settling) exactly, so
    ///   a ramp leaves a flat residual. A median over the whole buffer lags behind a ramp, and the luma then
    ///   crosses "median + threshold" again and again: a periodic train out of a ramp.
    /// - A flash is shorter than half the window, so it does not move the median.
    /// - Hysteresis: a pulse starts above `high` and ends under `low`, so noise on a threshold is one pulse.
    /// - The noise is the smaller of two robust estimates, because each is inflated by the signal in a
    ///   different regime: the deviation about the median by a broad waveform (a 35 % duty strobe at 17 Hz),
    ///   the difference of successive frames by a fast one (near fps / 2). For pure noise they agree.
    /// - The amplitude is never less than MIN_PULSE_AMPLITUDE, six sigmas of noise otherwise: noise alone
    ///   crosses nothing (the old threshold, mean + 0.6 sigma of the luma, was crossed by every scene).
    fn pulse_train(&self) -> Vec<f64> {
        let lum: Vec<f64> = self.buf.frames().map(|f| f.luminance).collect();
        if lum.len() < 8 {
            return Vec::new();
        }
        let times = self.buf.times();
        let frame_us = self.buf.frame_period_us().max(1.0);
        let window = (((BASELINE_WINDOW_US / frame_us) as usize) | 1).max(15);
        let resid = local_residual(&lum, window);
        let dev: Vec<f64> = resid.iter().map(|v| v.abs()).collect();
        let sigma_mad = 1.4826 * median(&dev);
        let diffs: Vec<f64> = resid.windows(2).map(|w| (w[1] - w[0]).abs()).collect();
        let sigma_diff = 1.4826 * median(&diffs) / std::f64::consts::SQRT_2;
        let sigma = sigma_mad.min(sigma_diff);
        let high = (PULSE_SIGMAS * sigma).max(MIN_PULSE_AMPLITUDE);
        let low = (0.5 * PULSE_SIGMAS * sigma).max(0.5 * MIN_PULSE_AMPLITUDE);
        let mut out = Vec::new();
        let mut above = false;
        for (t, r) in times.iter().zip(resid.iter()) {
            if !above && *r > high {
                out.push(*t);
                above = true;
            } else if above && *r < low {
                above = false;
            }
        }
        out
    }

    #[allow(dead_code)]
    fn stats(&self) -> (f64, f64) {
        let lum: Vec<f64> = self.buf.frames().map(|f| f.luminance).collect();
        if lum.is_empty() {
            return (0.0, 0.0);
        }
        let mean = lum.iter().sum::<f64>() / lum.len() as f64;
        let var = lum.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / lum.len() as f64;
        (mean, var.sqrt())
    }

    fn search(&mut self) -> Command {
        if self.buf.len() < self.warmup_frames {
            self.last_command = Command {
                exposure_us: search_exposure(&self.limits, 1e6 / self.last_command.fps.max(1.0)),
                fps: self.last_command.fps,
                ..Command::default()
            };
            return self.last_command;
        }

        let frame_us = self.buf.frame_period_us();
        // The search band is bounded by the sampling rate: frame-mean luma sampled at fps
        // cannot resolve a strobe above fps/2.
        let max_hz = (1e6 / frame_us / 2.0 * 0.8).min(60.0);

        let pulses = self.pulse_train();

        // A strobe is a regular train of events well above the noise: enough pulses, in phase with one
        // period (the Rayleigh statistic of their times). The threshold used to be mean + 0.6 sigma of
        // the luma, which noise crosses in every scene, and the autocorrelation guess locked on it: 100 %
        // of the simulated scenes without a strobe locked, and so did a Pixel 10 Pro XL in a dark room
        // (a lock of 9998 us carried to the recording). Without a coherent train there is no lock.
        // The train must still be going: a buffer that remembers the pulses of a strobe that stopped
        // seconds ago would lock, and `track` would drop the lock at once (and recurse).
        let now = self.buf.frames().last().map(|f| f.t_us).unwrap_or(0.0);
        let last_pulse = pulses.last().copied().unwrap_or(f64::NEG_INFINITY);
        let period = match pulse_period(&pulses, 1.0, max_hz.max(4.0)) {
            Some((p, r))
                if r >= MIN_PULSE_COHERENCE
                    && pulses.len() >= MIN_PULSES
                    && now - last_pulse <= (3.0 * p).max(2e6) =>
            {
                Some(p)
            }
            _ => None,
        };
        if let Some(guess) = period {
            if let Some(est) = fit_with_seed(&pulses, guess) {
                if est.confidence >= 0.45 {
                    let mut tracker = PulseTracker::new(est);
                    tracker.mark_seen(&pulses);
                    self.tracker = Some(tracker);
                    self.state = State::Locked;
                    return self.track();
                }
            }
        }

        self.last_command = Command {
            exposure_us: search_exposure(&self.limits, 1e6 / self.last_command.fps.max(1.0)),
            fps: self.last_command.fps,
            ..Command::default()
        };
        self.last_command
    }

    fn track(&mut self) -> Command {
        let pulses = self.pulse_train();

        if let Some(tracker) = &mut self.tracker {
            tracker.update(&pulses);
        }

        let est = match self.tracker.as_ref() {
            Some(tracker) => tracker.est,
            None => {
                self.state = State::Search;
                return self.search();
            }
        };
        // No loss-of-lock rule on a missing pulse: a working lock removes the pulses from the frames (an
        // exposure of whole periods integrates them away, a gap exposure excludes them), so their absence
        // proves nothing. The protection against a false lock is in `search`: a robust threshold, a coherent
        // train and a recent last pulse.
        let candidates = solve(&est, &self.limits);
        if candidates.is_empty() {
            self.state = State::Search;
            return self.search();
        }

        let best = candidates[0];
        // Gap and Flash throw away light, so gain has to make up the difference.
        let iso = (self.limits.min_iso * (self.limits.max_exposure_us / best.exposure_us))
            .clamp(self.limits.min_iso, self.limits.max_iso);
        self.last_command = Command {
            exposure_us: best.exposure_us,
            fps: best.fps,
            iso,
            mode: best.mode,
            state: State::Locked,
            period_us: est.period_us,
            confidence: est.confidence,
            drift_us_per_frame: best.drift_us_per_frame,
        };
        self.last_command
    }
}

/// Search with an exposure of most of a frame: a flash lands in some frame's window almost always, whatever
/// the strobe rate. With 8 ms of a 33 ms frame a 10 Hz strobe (exactly 3 frames per period, so the phase never
/// moves) whose flash fell in the 25 ms gap was never seen: measured on a Pixel 10 Pro XL running at 30 fps in a
/// dark room. The pulse is a smaller part of a longer exposure, but the light it adds is the same.
/// Luma minus its centred running median over `window` samples (truncated at the ends of the buffer).
fn local_residual(lum: &[f64], window: usize) -> Vec<f64> {
    let half = window / 2;
    let mut out = Vec::with_capacity(lum.len());
    let mut buf: Vec<f64> = Vec::with_capacity(window);
    for i in 0..lum.len() {
        let lo = i.saturating_sub(half);
        let hi = (i + half + 1).min(lum.len());
        buf.clear();
        buf.extend_from_slice(&lum[lo..hi]);
        out.push(lum[i] - median(&buf));
    }
    out
}

fn median(v: &[f64]) -> f64 {
    let mut x = v.to_vec();
    x.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    if x.is_empty() {
        0.0
    } else if x.len() % 2 == 1 {
        x[x.len() / 2]
    } else {
        0.5 * (x[x.len() / 2 - 1] + x[x.len() / 2])
    }
}

/// Pulse threshold: this many robust sigmas above the median, and at least MIN_PULSE_AMPLITUDE (0..1 luma).
/// Length of the running median that gives the baseline of the luma.
const BASELINE_WINDOW_US: f64 = 2.0e6;
const PULSE_SIGMAS: f64 = 6.0;
const MIN_PULSE_AMPLITUDE: f64 = 0.04;
/// Smallest Rayleigh statistic of the pulse times for a train to count as one period (1 = all in phase).
const MIN_PULSE_COHERENCE: f64 = 0.7;
const MIN_PULSES: usize = 4;

fn search_exposure(limits: &CameraLimits, frame_us: f64) -> f64 {
    let target = (SEARCH_FRAME_FRACTION * frame_us).max(SEARCH_EXPOSURE_US);
    limits.min_exposure_us.max(limits.max_exposure_us.min(target))
}
