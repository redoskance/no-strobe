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
    period_err: f64,
    exposure_us: f64,
    mode: String,
}

fn run(f_hz: f64, duty: f64, fps: f64, frames: usize) -> Outcome {
    let period = 1e6 / f_hz;
    let flash = (period * duty).max(500.0);
    let frame_us = 1e6 / fps;
    let mut c = Controller::new(CameraLimits::typical_phone());
    let mut t = 0.0;
    let mut exposure = 8_000.0;
    let mut locked_at = None;
    let mut last = None;
    for i in 0..frames {
        let lum = synth(t, exposure, period, 0.37 * period, flash);
        let cmd = c.on_frame(t, lum);
        if cmd.exposure_us > 0.0 {
            exposure = cmd.exposure_us;
        }
        if cmd.state == State::Locked && locked_at.is_none() {
            locked_at = Some(i);
        }
        last = Some(cmd);
        t += frame_us;
    }
    let cmd = last.unwrap();
    Outcome {
        locked_at,
        period_err: if cmd.period_us > 0.0 { (cmd.period_us - period).abs() / period } else { 1.0 },
        exposure_us: cmd.exposure_us,
        mode: format!("{:?}", cmd.mode),
    }
}

fn ok(o: &Outcome) -> bool {
    o.locked_at.is_some() && o.period_err < 0.02
}

/// What the detector can resolve from the mean luma of frames. The strobe period comes from the pulse
/// TIMES (epoch folding), which no longer mistakes a strobe for a multiple of itself.
///
/// Limits that remain, by physics and not by bug, and are not asserted:
/// - above half the frame rate (15 Hz at 30 fps) the frames alias the strobe: 60 fps is needed up to 30 Hz;
/// - a flash of 5 % of the period is shorter than the gap between two 8 ms exposure windows at 30 fps and
///   can fall in the gap every time (5 and 10 Hz are then not seen); 20 Hz at 60 fps with such a flash too.
#[test]
fn strobe_from_1_to_20_hz_locks_on_the_right_period_in_band() {
    let mut failures = Vec::new();
    for (fps, duty, freqs) in [
        (60.0, 0.35, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 13.0, 15.0, 17.0, 20.0]),
        (60.0, 0.15, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 13.0, 15.0, 17.0, 20.0]),
        (60.0, 0.05, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 13.0, 15.0, 17.0]),
        (30.0, 0.35, vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0]),
        (30.0, 0.15, vec![1.0, 2.0, 3.0, 5.0, 7.0]),
    ] {
        for f in freqs {
            let o = run(f, duty, fps, 600);
            if !ok(&o) {
                failures.push(format!(
                    "{f} Hz, duty {duty}, {fps} fps: locked {:?}, period error {:.0} %, {} at {:.0} us",
                    o.locked_at, o.period_err * 100.0, o.mode, o.exposure_us
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
        for duty in [0.05, 0.15, 0.35] {
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
