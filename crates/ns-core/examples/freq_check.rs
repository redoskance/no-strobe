//! What the app gets as the flicker frequency after a lock: the scan-null spacing, not 1 / exposure.
//!     cargo run -p ns-core --example freq_check [f_hz] [frames]
use ns_core::camera::CameraLimits;
use ns_core::controller::State;
use ns_core::pwm::synthetic_profile;
use ns_core::pwm_controller::PwmController;

fn main() {
    let f: f64 = std::env::args().nth(1).and_then(|v| v.parse().ok()).unwrap_or(500.0);
    let frames: usize = std::env::args().nth(2).and_then(|v| v.parse().ok()).unwrap_or(400);
    let limits = CameraLimits {
        min_exposure_us: 100.0,
        max_exposure_us: 30000.0,
        sustain_us: 5000.0,
        supported_fps: vec![30.0],
        min_iso: 100.0,
        max_iso: 6400.0,
    };
    let line = 40000.0;
    let rows = 1080;
    let mut c = PwmController::new(limits, line);
    let mut phase = 0.0;
    let mut last = c.on_profile(&synthetic_profile(1000.0, f, phase, 0.3, line, rows));
    for i in 0..frames {
        phase += 0.01;
        let p = synthetic_profile(last.exposure_us, f, phase, 0.3, line, rows);
        last = c.on_profile(&p);
        if last.state == State::Locked {
            let t = 1e6 / f;
            let k = (last.exposure_us / t).round();
            let (prow, pconf) = c.debug_period();
            let (lexp, fest) = c.debug_exposure();
            let (fctrl, from_rows) = c.debug_f();
            println!(
                "  controller f_hz {:.1} (from rows: {from_rows}), f_est_hz {:.1}, k at f_hz {:.3}",
                fctrl,
                fest,
                lexp * fctrl / 1e6
            );
            println!(
                "  last commanded exposure {:.1} us, f_est_hz {:.1}, k = {:.3}",
                lexp,
                fest,
                lexp * fest / 1e6
            );
            println!(
                "locked at frame {i}: exposure {:.1} us (k={k} of {f} Hz), frequency_hz() = {:.1} (asked {f})",
                last.exposure_us,
                c.frequency_hz()
            );
            println!(
                "  scan candidates {:?} us, period {:.1} rows conf {:.2} (row time {:.2} us)",
                c.debug_scan_cands(),
                prow,
                pconf,
                1e6 / 30.0 / rows as f64
            );
            return;
        }
    }
    println!("never locked; frequency_hz() = {:.1}, state {:?}", c.frequency_hz(), last.state);
}
