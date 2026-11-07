//! Low-frequency strobe (1-20 Hz): detection and lock over a sweep of frequencies, flash widths and
//! frame rates, in closed loop (the luminance of a frame depends on the exposure the controller chose).
use ns_core::camera::CameraLimits;
use ns_core::controller::{Controller, State};

/// Light is `flash_us` wide every `period_us`; a frame integrates the overlap with [t - exposure, t].
fn synth(t_us: f64, exposure_us: f64, period_us: f64, phase_us: f64, flash_us: f64) -> f64 {
    let start = t_us - exposure_us;
    let end = t_us;
    let mut overlap = 0.0;
    let mut m = ((start - phase_us) / period_us).floor();
    while phase_us + m * period_us <= end {
        let f_start = phase_us + m * period_us;
        overlap += (end.min(f_start + flash_us) - start.max(f_start)).max(0.0);
        m += 1.0;
    }
    0.2 + 0.8 * (overlap / exposure_us.max(1.0))
}

struct Outcome {
    locked_at: Option<usize>,
    /// Error of the period the controller reported at the moment it locked, and at the end of the run.
    period_err_at_lock: f64,
    period_err: f64,
    exposure_us: f64,
    mode: String,
}

fn run(f_hz: f64, duty: f64, fps: f64, frames: usize) -> Outcome {
    let period = 1e6 / f_hz;
    let flash = (period * duty).max(500.0);
    let mut c = Controller::new(CameraLimits::typical_phone());
    let mut t = 0.0;
    let mut exposure = 8_000.0;
    // The camera follows the frame rate the controller asks for (a locked strobe may need 30 or 24 fps).
    let mut current_fps = fps;
    let mut locked_at = None;
    let mut err_at_lock = 1.0;
    let mut last = None;
    for i in 0..frames {
        let lum = synth(t, exposure, period, 0.37 * period, flash);
        let cmd = c.on_frame(t, lum);
        if cmd.exposure_us > 0.0 {
            exposure = cmd.exposure_us;
        }
        if cmd.fps > 0.0 {
            // The camera cannot run faster than the rate under test (a phone in a dark room stays at 30 fps).
            current_fps = cmd.fps.min(fps);
        }
        if cmd.state == State::Locked && locked_at.is_none() {
            locked_at = Some(i);
            err_at_lock = if cmd.period_us > 0.0 { (cmd.period_us - period).abs() / period } else { 1.0 };
        }
        last = Some(cmd);
        t += 1e6 / current_fps;
    }
    let cmd = last.unwrap();
    Outcome {
        locked_at,
        period_err_at_lock: err_at_lock,
        period_err: if cmd.period_us > 0.0 { (cmd.period_us - period).abs() / period } else { 1.0 },
        exposure_us: cmd.exposure_us,
        mode: format!("{:?}", cmd.mode),
    }
}

fn ok(o: &Outcome) -> bool {
    o.locked_at.is_some() && o.period_err_at_lock < 0.02 && o.period_err < 0.02
}

/// What the detector can resolve from the mean luma of frames. The strobe period comes from the pulse
/// TIMES (epoch folding), which no longer mistakes a strobe for a multiple of itself, and the search
/// exposure covers most of the frame so that a flash cannot fall in the gap between two exposure windows.
///
/// Limits that remain, by physics and not by bug, and are not asserted:
/// - above half the frame rate (15 Hz at 30 fps) the frames alias the strobe: 60 fps is needed up to 30 Hz.
///   The phone may run at 30 fps even when 60 is asked (some do in a dark room);
/// - a flash of about half the period seen through a window of most of a 33 ms frame at 7 or 10 Hz is a
///   smooth wave of two or three frames: the edges are lost.
#[test]
fn strobe_from_1_to_20_hz_locks_on_the_right_period_in_band() {
    let all = vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 13.0, 15.0, 17.0, 20.0];
    let mut failures = Vec::new();
    for (fps, duty, freqs) in [
        (60.0, 0.15, all.clone()),
        (60.0, 0.05, all.clone()),
        // A broad flash (35 % of the period) at 17 Hz is a smooth wave, not a pulse train: not asserted.
        (60.0, 0.35, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 13.0, 15.0, 20.0]),
        (30.0, 0.35, vec![1.0, 2.0, 3.0, 5.0, 10.0]),
        (30.0, 0.15, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0]),
        (30.0, 0.05, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0]),
        // A beacon that is lit half of the time or more: the median of the luma sits on the lit level, and
        // the dark gaps are the events.
        (60.0, 0.5, vec![2.0, 3.0, 5.0, 7.0, 10.0, 15.0]),
        // (17 Hz at 70 % duty is a smooth wave: every second dip stays shallow and the events come every other
        // cycle, which reads as 8.5 Hz once the lock waits for a second of train. Not asserted.)
        (60.0, 0.7, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 13.0, 15.0, 20.0]),
        (30.0, 0.5, vec![1.0, 2.0, 3.0, 5.0]),
        (30.0, 0.7, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0]),
    ] {
        for f in freqs {
            let o = run(f, duty, fps, 600);
            if !ok(&o) {
                failures.push(format!(
                    "{f} Hz, duty {duty}, {fps} fps: locked {:?}, period error {:.0} % (at the lock {:.0} %), {} at {:.0} us",
                    o.locked_at, o.period_err * 100.0, o.period_err_at_lock * 100.0, o.mode, o.exposure_us
                ));
            }
        }
    }
    assert!(failures.is_empty(), "strobe sweep failures:\n{}", failures.join("\n"));
}

/// Never lock on a wrong period inside the band: a wrong lock applies a wrong exposure to the video.
#[test]
fn no_wrong_lock_in_the_resolvable_band() {
    for fps in [30.0, 60.0] {
        for duty in [0.05, 0.15, 0.35] {
            for f in [1.0, 2.0, 3.0, 5.0, 7.0, 10.0] {
                let o = run(f, duty, fps, 600);
                if o.locked_at.is_some() {
                    assert!(o.period_err < 0.02, "{f} Hz, duty {duty}, {fps} fps: locked on a period {:.0} % off", o.period_err * 100.0);
                }
            }
        }
    }
}

/// `REPORT=1 cargo test --test strobe_sweep -- --nocapture` prints the whole grid.
#[test]
fn report_strobe_sweep() {
    if std::env::var("REPORT").is_err() {
        return;
    }
    for fps in [30.0, 60.0] {
        for duty in [0.05, 0.15, 0.35, 0.5, 0.7] {
            let mut row = String::new();
            for f in [1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 13.0, 15.0, 17.0, 20.0] {
                let o = run(f, duty, fps, 600);
                row += &format!(
                    " {f:>4.0}Hz:{}",
                    match o.locked_at {
                        Some(i) if o.period_err < 0.02 => format!("ok@{i}"),
                        Some(i) => format!("BAD({:.0}%)@{i}", o.period_err * 100.0),
                        None => "none".to_string(),
                    }
                );
            }
            eprintln!("fps {fps:>3.0} duty {duty:.2}:{row}");
        }
    }
}

/// xorshift: a deterministic generator without a dependency.
struct Rng(u64);
impl Rng {
    fn unit(&mut self) -> f64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 11) as f64 / (1u64 << 53) as f64
    }
    /// Roughly Gaussian (sum of 4 uniforms), unit variance.
    fn gauss(&mut self) -> f64 {
        ((0..4).map(|_| self.unit()).sum::<f64>() - 2.0) * 1.732
    }
}

/// A scene with NO strobe: constant brightness, sensor noise, a slow drift (the light of the room), a
/// slow triangular ramp (stage lights fading up and down over 10 s) and now and then a step of brightness
/// (the auto-exposure settling). Locks seen on a phone in a dark room: the controller reported
/// a lock of 9998 us and the app carried it to the recording.
fn no_strobe_locks(fps: f64, noise: f64, drift: f64, ramp: f64, steps: f64, seed: u64) -> usize {
    let mut rng = Rng(seed.wrapping_mul(0x9E3779B97F4A7C15) | 1);
    let mut c = Controller::new(CameraLimits::typical_phone());
    let frame_us = 1e6 / fps;
    let mut t = 0.0;
    let mut level = 0.0;
    let mut locked_frames = 0;
    for i in 0..1800 {
        let x = i as f64 / (fps * 10.0);
        let tri = 2.0 * (x - (x + 0.5).floor()).abs(); // 0..1 triangle, 10 s period
        let slow = drift * (2.0 * std::f64::consts::PI * (i as f64) / (fps * 20.0)).sin();
        if rng.unit() < steps {
            level = if level > 0.0 { 0.0 } else { 0.15 };
        }
        let lum = (0.15 + slow + ramp * tri + level + noise * rng.gauss()).clamp(0.0, 1.0);
        if c.on_frame(t, lum).state == State::Locked {
            locked_frames += 1;
        }
        t += frame_us;
    }
    locked_frames
}

#[test]
fn a_scene_without_a_strobe_never_locks() {
    let mut failures = Vec::new();
    for fps in [30.0, 60.0] {
        for (noise, drift, ramp, steps) in [
            (0.002, 0.0, 0.0, 0.0),
            (0.01, 0.0, 0.0, 0.0),
            (0.03, 0.0, 0.0, 0.0),
            (0.08, 0.0, 0.0, 0.0),
            (0.01, 0.03, 0.0, 0.0),
            (0.01, 0.0, 0.3, 0.0),   // stage lights fading up and down
            (0.01, 0.0, 0.0, 0.004), // auto-exposure steps
            (0.03, 0.03, 0.2, 0.004),
        ] {
            for seed in 1..=6u64 {
                let n = no_strobe_locks(fps, noise, drift, ramp, steps, seed);
                if n > 0 {
                    failures.push(format!("{fps} fps, noise {noise}, drift {drift}, ramp {ramp}, steps {steps}, seed {seed}: locked for {n} frames"));
                }
            }
        }
    }
    assert!(failures.is_empty(), "false strobe locks:\n{}", failures.join("\n"));
}

#[test]
fn report_no_strobe_locks() {
    if std::env::var("REPORT").is_err() {
        return;
    }
    for fps in [30.0, 60.0] {
        for (noise, drift) in [(0.002, 0.0), (0.01, 0.0), (0.03, 0.0), (0.01, 0.03), (0.08, 0.0)] {
            let locks: Vec<usize> = (1..=10).map(|s| no_strobe_locks(fps, noise, drift, 0.0, 0.0, s)).collect();
            let runs = locks.iter().filter(|n| **n > 0).count();
            eprintln!("NOSTROBE fps {fps:>3.0} noise {noise:.3} drift {drift:.2}: {runs}/10 runs locked {locks:?}");
        }
    }
}


/// A strobe for `on_frames` frames, then constant light for `off_frames`: the states seen, frame by frame.
fn strobe_then_nothing(f_hz: f64, duty: f64, fps: f64, on_frames: usize, off_frames: usize) -> Vec<(State, f64, f64)> {
    let period = 1e6 / f_hz;
    let flash = (period * duty).max(500.0);
    let mut c = Controller::new(CameraLimits::typical_phone());
    let mut t = 0.0;
    let mut exposure = 8_000.0;
    let mut current_fps = fps;
    let mut out = Vec::new();
    for i in 0..(on_frames + off_frames) {
        let lum = if i < on_frames { synth(t, exposure, period, 0.37 * period, flash) } else { 0.2 };
        let cmd = c.on_frame(t, lum);
        if cmd.exposure_us > 0.0 {
            exposure = cmd.exposure_us;
        }
        if cmd.fps > 0.0 {
            current_fps = cmd.fps.min(fps);
        }
        out.push((cmd.state, cmd.period_us, t));
        t += 1e6 / current_fps;
    }
    out
}

/// The lock is checked again every 20 s (the pulses are hidden by a working lock, so nothing else can end
/// it). A strobe that goes on must come through the checks and stay locked.
#[test]
fn a_strobe_that_goes_on_stays_locked_through_the_rechecks() {
    for (f, duty, fps) in [
        (1.0, 0.35, 30.0),
        (2.0, 0.15, 30.0),
        (5.0, 0.5, 30.0),
        (3.0, 0.15, 60.0),
        (10.0, 0.05, 60.0),
        (15.0, 0.15, 60.0),
    ] {
        let frames = (fps * 100.0) as usize; // 100 s: four checks
        let states = strobe_then_nothing(f, duty, fps, frames, 0);
        let first = states.iter().position(|s| s.0 == State::Locked).expect("no lock");
        let after = &states[first..];
        // In time, not in frames: a locked camera may run at 15 fps and a searching one at 30 or 60.
        let mut locked_us = 0.0;
        for w in after.windows(2) {
            if w[0].0 == State::Locked {
                locked_us += w[1].2 - w[0].2;
            }
        }
        let share = locked_us / (after.last().unwrap().2 - after[0].2);
        assert!(share > 0.75, "{f} Hz, duty {duty}, {fps} fps: locked only {:.0} % of the time after the lock", share * 100.0);
        let period = states.last().unwrap().1;
        assert!(period > 0.0 && (period - 1e6 / f).abs() < 0.03 * 1e6 / f, "{f} Hz: the period at the end is {period}");
    }
}

/// A light that blinked at a steady rate for a few seconds and stopped must not hold a lock: the recording
/// that follows would be exposed for a strobe that is not there.
#[test]
fn a_strobe_that_stops_releases_the_lock() {
    for (f, duty, fps) in [(2.0, 0.15, 30.0), (5.0, 0.35, 30.0), (10.0, 0.05, 60.0), (1.0, 0.15, 60.0)] {
        let on = (fps * 12.0) as usize;
        let off = (fps * 40.0) as usize;
        let states = strobe_then_nothing(f, duty, fps, on, off);
        assert!(states[..on].iter().any(|s| s.0 == State::Locked), "{f} Hz: it never locked");
        let end = states.last().unwrap().0;
        assert_eq!(end, State::Search, "{f} Hz, duty {duty}, {fps} fps: still locked 40 s after the strobe stopped");
    }
}

/// A period for which the camera has no exposure plan (a 1.2 Hz strobe on a camera whose slowest rate is 15 fps and
/// whose longest exposure is 16 s: the flash is longer than a frame, and so is the dark gap) used to lock, and
/// `track` then called `search`, which locked on the same pulses again: a stack overflow.
#[test]
fn a_period_with_no_exposure_plan_does_not_recurse() {
    let limits = CameraLimits {
        min_exposure_us: 123.0,
        max_exposure_us: 16_000_006.0,
        sustain_us: 0.0,
        supported_fps: vec![15.0, 24.0, 30.0, 60.0],
        min_iso: 36.0,
        max_iso: 4620.0,
    };
    for f_hz in [1.0, 1.2, 1.4] {
        let period = 1e6 / f_hz;
        let mut c = Controller::new(limits.clone());
        let mut t = 0.0;
        let mut exposure = 8_000.0;
        for _ in 0..1500 {
            let lum = synth(t, exposure, period, 0.37 * period, period * 0.1);
            let cmd = c.on_frame(t, lum);
            if cmd.exposure_us > 0.0 {
                exposure = cmd.exposure_us;
            }
            t += 1e6 / 30.0;
        }
    }
}

/// A scene without a strobe whose lights blink now and then, as a real room does (an indicator LED that flashes
/// on activity, a keyboard backlight): flat luma with brief bright frames that come in short bursts three
/// frames (100 ms) apart and then stay away for a second or two. Four pulses of such a source line up on some
/// period of the Rayleigh scan about once in three tries; the controller used to lock on one (a 9.7 Hz lock after
/// 3 s on a recording of a real room) and keep it, because nothing released a lock. A strobe has a pulse in
/// nearly every cycle; this source has one in a cycle of three at most.
fn blinking_light_scene(seed: u64, fps: f64, seconds: f64) -> Vec<f64> {
    let n = (fps * seconds) as usize;
    let mut rng = Rng(seed.wrapping_mul(0x9E3779B97F4A7C15) | 1);
    let mut lum = vec![0.5f64; n];
    let mut i = 20usize;
    while i < n {
        // A burst of 1 to 4 pulses, 3 frames apart (a pulse is one frame, sometimes two).
        let k = 1 + (rng.unit() * 4.0) as usize;
        for j in 0..k {
            let at = i + 3 * j;
            if at + 1 < n {
                lum[at] += 0.12;
                if rng.unit() < 0.3 {
                    lum[at + 1] += 0.06;
                }
            }
        }
        i += 3 * k + 4 + (rng.unit() * 55.0) as usize;
    }
    for v in lum.iter_mut() {
        *v += 0.002 * rng.gauss();
    }
    lum
}

/// Five blinks of a random source can be spaced alike by chance, so a lock now and then is not a defect; what
/// would be one is a lock that is not let go (the re-check drops it within about 25 s) or one that comes often.
#[test]
fn a_scene_whose_lights_blink_now_and_then_does_not_hold_a_lock() {
    let (mut locked_total, mut frames_total) = (0usize, 0usize);
    for fps in [30.0, 60.0] {
        for seed in 1..=12u64 {
            let lum = blinking_light_scene(seed, fps, 70.0);
            let mut c = Controller::new(CameraLimits::typical_phone());
            let mut locked = 0usize;
            for (i, l) in lum.iter().enumerate() {
                if c.on_frame(i as f64 * 1e6 / fps, *l).state == State::Locked {
                    locked += 1;
                }
            }
            assert!(
                (locked as f64) / fps < 30.0,
                "held a lock for {:.0} s on a blinking light (seed {seed}, {fps} fps)",
                locked as f64 / fps
            );
            locked_total += locked;
            frames_total += lum.len();
        }
    }
    let share = locked_total as f64 / frames_total as f64;
    assert!(share < 0.05, "locked {:.1} % of the time on blinking lights", share * 100.0);
}
