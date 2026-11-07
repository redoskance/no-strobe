use crate::camera::CameraLimits;
use crate::detector::{fit_with_seed, fold_ratio, pulse_period, train_regularity, Estimate, PulseTracker};
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
    /// The frame rate the search asks for: the highest the camera supports.
    search_fps: f64,
    /// Frames still to ignore: the exposure and the frame rate have just changed, and for a frame or two the
    /// camera is still running on the old ones. A flash seen through the old exposure window has a different
    /// time, which biased the period of a lock fitted on the first pulses (3 % off at 3 Hz).
    settle_frames: usize,
    /// Time of the frame at which the lock was last confirmed (found, or re-validated).
    validated_us: f64,
    /// A re-validation under way: the search exposure is back, and the pulses of the strobe are collected
    /// again from an empty buffer.
    probing: bool,
}

/// Frames ignored when the controller starts, and after a lock is dropped.
const SETTLE_START: usize = 12;
const SETTLE_AFTER_LOCK: usize = 8;
/// A lock is checked again this often. Nothing else can end it: a working lock hides the pulses (an exposure
/// of whole periods integrates them away, a gap exposure excludes them), so their absence proves nothing. A
/// lock made on a light that blinked for a few seconds would stay for ever, and the recording that follows
/// would be exposed for a strobe that is not there.
const REVALIDATE_AFTER_US: f64 = 20.0e6;
/// The check lasts this many periods of the strobe, but at least 2 s and at most 4 s: three cycles are enough
/// to fold the luma on the period of a 1 Hz strobe. The status goes back to "search" while the check runs
/// (the app keeps a lock for 4 s to carry it into a recording).
const PROBE_CYCLES: f64 = 3.5;
const PROBE_MIN_US: f64 = 2.0e6;
const PROBE_MAX_US: f64 = 4.0e6;
/// The re-fitted period may differ from the locked one by this much (the strobe may drift a little).
const REVALIDATE_PERIOD_TOLERANCE: f64 = 0.03;
/// The check also passes when the luma folded on the locked period has a fundamental of at least this
/// amplitude (0..1 luma), and this many times the amplitude that noise alone gives (see `fold_ratio`).
const MIN_FOLD_AMPLITUDE: f64 = 0.01;
const MIN_FOLD_RATIO: f64 = 5.0;

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
            search_fps: fps,
            settle_frames: SETTLE_START,
            validated_us: 0.0,
            probing: false,
        }
    }

    pub fn on_frame(&mut self, t_us: f64, luminance: f64) -> Command {
        if self.settle_frames > 0 {
            self.settle_frames -= 1;
            return self.last_command;
        }
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
    /// - Two polarities. A flash shorter than half the window does not move the median and gives a rising
    ///   edge above it. A flash that lights half of the frames or more (a beacon of 50 % duty; a long exposure
    ///   through a broad flash) puts the median on the LIT level, and it is the dark gaps that stand out: the
    ///   falling edge into each gap is the event. The polarity with more events is kept.
    /// - Hysteresis: a pulse starts beyond `high` and ends inside `low`, so noise on a threshold is one pulse.
    /// - The noise is the smallest of three robust estimates, because each is inflated by the signal in a
    ///   different regime: the deviation about the median by a broad waveform (a 35 % duty strobe at 17 Hz),
    ///   the difference of successive frames by a fast one (near fps / 2), and each half of the residuals by
    ///   the light that the signal adds on its side (signal is on one side only, so the other half is noise).
    ///   For pure noise they agree.
    /// - The amplitude is never less than MIN_PULSE_AMPLITUDE, six sigmas of noise otherwise: noise alone
    ///   crosses nothing (the old threshold, mean + 0.6 sigma of the luma, was crossed by every scene).
    pub fn pulse_train(&self) -> Vec<f64> {
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
        let side = |keep: fn(f64) -> bool| -> f64 {
            let v: Vec<f64> = resid.iter().filter(|r| keep(**r)).map(|r| r.abs()).collect();
            if v.len() >= 8 {
                1.4826 * median(&v)
            } else {
                f64::INFINITY
            }
        };
        let sigma = sigma_mad.min(sigma_diff).min(side(|r| r <= 0.0)).min(side(|r| r >= 0.0));
        let high = (PULSE_SIGMAS * sigma).max(MIN_PULSE_AMPLITUDE);
        let low = (0.5 * PULSE_SIGMAS * sigma).max(0.5 * MIN_PULSE_AMPLITUDE);
        let up = edges(&times, &resid, high, low);
        let flipped: Vec<f64> = resid.iter().map(|r| -r).collect();
        let down = edges(&times, &flipped, high, low);
        if down.len() > up.len() {
            down
        } else {
            up
        }
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

    /// The command of the search: the highest frame rate, and an exposure of most of the frame the camera
    /// ACTUALLY delivers. A phone asked for 60 fps often runs at 30 (in a dim room): an
    /// exposure sized for 60 covered 45 % of each 30 fps frame, and a flash fell in the gap more than
    /// half of the time.
    fn search_command(&mut self) -> Command {
        let commanded_us = 1e6 / self.search_fps.max(1.0);
        let frame_us = if self.buf.len() >= 8 { self.buf.frame_period_us().max(commanded_us) } else { commanded_us };
        self.last_command = Command {
            exposure_us: search_exposure(&self.limits, frame_us),
            fps: self.search_fps,
            ..Command::default()
        };
        self.last_command
    }

    fn search(&mut self) -> Command {
        if self.buf.len() < self.warmup_frames {
            return self.search_command();
        }

        let frame_us = self.buf.frame_period_us();
        // The search band is bounded by the sampling rate: frame-mean luma sampled at fps
        // cannot resolve a strobe above fps/2.
        let max_hz = (1e6 / frame_us / 2.0 * 0.8).min(60.0);

        let pulses = self.pulse_train();

        // A strobe is a regular train of events well above the noise: enough pulses, in phase with one
        // period (the Rayleigh statistic of their times), and a pulse in nearly every cycle. The threshold
        // used to be mean + 0.6 sigma of the luma, which noise crosses in every scene, and the
        // autocorrelation guess locked on it: 100 % of the simulated scenes without a strobe locked, and so
        // did a phone in a dark room (a lock of 9998 us carried to the recording). The Rayleigh
        // statistic alone is not enough either: four pulses of a light that blinks now and then line up on
        // some period of the scan about once in three tries, and a real scene did (a 9.7 Hz lock on pulses
        // that came in 1 cycle of 3). Without a coherent AND complete train there is no lock.
        // The train must still be going: a buffer that remembers the pulses of a strobe that stopped
        // seconds ago would lock, and `track` would drop the lock at once.
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
                if est.confidence >= 0.45 && self.is_a_strobe(&pulses, &est) {
                    let mut tracker = PulseTracker::new(est);
                    tracker.mark_seen(&pulses);
                    self.tracker = Some(tracker);
                    self.state = State::Locked;
                    self.validated_us = now;
                    self.probing = false;
                    return self.track();
                }
            }
        }
        self.search_command()
    }

    /// The fitted train is a strobe: a pulse in nearly every cycle, most pulses on the train, and an
    /// exposure that can be planned for its period.
    fn is_a_strobe(&self, pulses: &[f64], est: &Estimate) -> bool {
        let (completeness, matched, cycles) = train_regularity(pulses, est);
        let span = pulses.last().copied().unwrap_or(0.0) - pulses.first().copied().unwrap_or(0.0);
        cycles >= MIN_TRAIN_CYCLES
            && span >= MIN_TRAIN_SPAN_US
            && completeness >= MIN_TRAIN_COMPLETENESS
            && matched >= MIN_TRAIN_MATCHED
            && !solve(est, &self.limits).is_empty()
    }

    fn track(&mut self) -> Command {
        let (first_us, now) = match (self.buf.frames().next(), self.buf.frames().last()) {
            (Some(a), Some(b)) => (a.t_us, b.t_us),
            _ => (0.0, 0.0),
        };
        if self.probing {
            let period = self.tracker.as_ref().map(|t| t.est.period_us).unwrap_or(0.0);
            let length = (PROBE_CYCLES * period).clamp(PROBE_MIN_US, PROBE_MAX_US);
            if now - first_us < length {
                return self.probe_command();
            }
            self.probing = false;
            if !self.still_a_strobe() {
                return self.drop_lock();
            }
            self.validated_us = now;
            // The buffer holds the probe only: the lock goes on from here.
        } else if now - self.validated_us >= REVALIDATE_AFTER_US {
            self.probing = true;
            self.buf.clear();
            self.settle_frames = SETTLE_AFTER_LOCK;
            return self.probe_command();
        }

        let pulses = self.pulse_train();

        if let Some(tracker) = &mut self.tracker {
            tracker.update(&pulses);
        }

        let est = match self.tracker.as_ref() {
            Some(tracker) => tracker.est,
            None => return self.drop_lock(),
        };
        // No loss-of-lock rule on a missing pulse: a working lock removes the pulses from the frames (an
        // exposure of whole periods integrates them away, a gap exposure excludes them), so their absence
        // proves nothing. The protection against a false lock is in `search`: a robust threshold, a coherent
        // and complete train and a recent last pulse.
        let candidates = solve(&est, &self.limits);
        if candidates.is_empty() {
            return self.drop_lock();
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

    /// The search exposure while a lock is being checked; the status keeps the period under test.
    fn probe_command(&mut self) -> Command {
        let period = self.tracker.as_ref().map(|t| t.est.period_us).unwrap_or(0.0);
        let mut c = self.search_command();
        c.period_us = period;
        self.last_command = c;
        c
    }

    /// The pulses collected since the check began still make the locked strobe: the same period (a few
    /// percent), a pulse in nearly every cycle, most pulses on the train. A slow strobe gives 3 or 4 pulses
    /// in the check, which is enough to confirm a known period but not to find one.
    fn still_a_strobe(&mut self) -> bool {
        let locked = match self.tracker.as_ref() {
            Some(t) => t.est,
            None => return false,
        };
        let pulses = self.pulse_train();
        let by_pulses = pulses.len() >= 3
            && fit_with_seed(&pulses, locked.period_us).is_some_and(|est| {
                let (completeness, matched, cycles) = train_regularity(&pulses, &est);
                let same_period =
                    (est.period_us - locked.period_us).abs() <= REVALIDATE_PERIOD_TOLERANCE * locked.period_us;
                cycles >= 3 && completeness >= MIN_TRAIN_COMPLETENESS && matched >= MIN_TRAIN_MATCHED && same_period
            });
        // The pulse times miss a strobe whose duty is near 50 %, or whose period is long against the window of
        // the baseline: the fold of the luma does not.
        let by_fold = || {
            let frames: Vec<(f64, f64)> = self.buf.frames().map(|f| (f.t_us, f.luminance)).collect();
            let (amplitude, ratio, _) = fold_ratio(&frames, locked.period_us);
            amplitude >= MIN_FOLD_AMPLITUDE && ratio >= MIN_FOLD_RATIO
        };
        if !(by_pulses || by_fold()) {
            return false;
        }
        if let Some(t) = &mut self.tracker {
            // The period found at the lock (from a longer span) stands; the phase is renewed from the pulses.
            if let Some(est) = fit_with_seed(&pulses, locked.period_us) {
                if pulses.len() >= 3 {
                    t.est.phase_us = est.phase_us;
                }
            }
            t.mark_seen(&pulses);
        }
        true
    }

    /// Back to the search with an empty history: the pulses that made the lock are forgotten, so the same
    /// train cannot lock again at once. (Going back through `search` here recursed for ever: `search` locked
    /// on the same pulses, `track` found no exposure for the period and called `search` again; a stack
    /// overflow that no plan of the solver covers, e.g. a 1.2 Hz strobe on a camera of 15 fps at best.)
    fn drop_lock(&mut self) -> Command {
        self.tracker = None;
        self.state = State::Search;
        self.probing = false;
        self.buf.clear();
        self.settle_frames = SETTLE_AFTER_LOCK;
        self.search_command()
    }
}

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

/// Times of the samples where `resid` rises above `high` (and stays above `low` until it falls back).
fn edges(times: &[f64], resid: &[f64], high: f64, low: f64) -> Vec<f64> {
    let mut out = Vec::new();
    // A pulse already under way at the first sample of the buffer has no known start: it is not an edge.
    let mut above = resid.first().is_some_and(|r| *r > high);
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

/// Length of the running median that gives the baseline of the luma.
const BASELINE_WINDOW_US: f64 = 2.0e6;
/// Pulse threshold: this many robust sigmas above the baseline, and at least MIN_PULSE_AMPLITUDE (0..1 luma).
const PULSE_SIGMAS: f64 = 6.0;
const MIN_PULSE_AMPLITUDE: f64 = 0.04;
/// Smallest Rayleigh statistic of the pulse times for a train to count as one period (1 = all in phase).
const MIN_PULSE_COHERENCE: f64 = 0.7;
const MIN_PULSES: usize = 4;
/// A strobe leaves a pulse in nearly every one of its cycles (the search exposure covers most of the frame);
/// a scene whose lights blink now and then does not. Share of the cycles, between the first and the last
/// pulse of the train, that hold a pulse. Measured at the moment of the lock: 1.0 for every simulated strobe
/// but one (0.67), 0.33 for the irregular pulses of a real scene.
const MIN_TRAIN_COMPLETENESS: f64 = 0.6;
/// Cycles with a pulse over all the pulses: nearly every pulse is the only one of its cycle, on the train.
const MIN_TRAIN_MATCHED: f64 = 0.8;
/// A train must be going on for a while: at least this many cycles with a pulse, over at least this long. A
/// light that blinks four times at 10 Hz and stops (an indicator LED during a transfer) is a perfect train of
/// four pulses over 0.3 s, and used to lock; a strobe lasts for seconds at least. (The span, not a larger
/// number of cycles: a 1 Hz strobe only has four pulses in the 4 s buffer of a 60 fps camera.)
const MIN_TRAIN_CYCLES: usize = 4;
const MIN_TRAIN_SPAN_US: f64 = 1.0e6;

/// Search with an exposure of most of a frame: a flash lands in some frame's window almost always, whatever
/// the strobe rate. With 8 ms of a 33 ms frame a 10 Hz strobe (exactly 3 frames per period, so the phase never
/// moves) whose flash fell in the 25 ms gap was never seen: measured on a phone running at 30 fps in a
/// dark room. The pulse is a smaller part of a longer exposure, but the light it adds is the same.
fn search_exposure(limits: &CameraLimits, frame_us: f64) -> f64 {
    let target = (SEARCH_FRAME_FRACTION * frame_us).max(SEARCH_EXPOSURE_US);
    limits.min_exposure_us.max(limits.max_exposure_us.min(target))
}
