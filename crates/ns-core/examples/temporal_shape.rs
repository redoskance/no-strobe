//! Shape of the temporal metric (the one the app uses on a real scene) around the k/f null, with
//! the phone's numbers: 500 Hz, 30 fps (the phase takes 3 values), 1080 rows. Prints the metric
//! for a trial of 3 frames at each exposure, so the hill climb's landscape is visible.
//!     cargo run -p ns-core --example temporal_shape [f_hz] [rows]
use ns_core::pwm::{banding_amplitude, synthetic_profile, temporal_banding};

fn main() {
    let f: f64 = std::env::args().nth(1).and_then(|v| v.parse().ok()).unwrap_or(500.0);
    let rows: usize = std::env::args().nth(2).and_then(|v| v.parse().ok()).unwrap_or(1080);
    let line = 40_000.0;
    let t_us = 1e6 / f;
    println!("f = {f} Hz, period {t_us:.1} us, {rows} rows, 3 frames per trial");
    println!("{:>9} {:>7} {:>12} {:>12}", "exposure", "cycles", "temporal", "spatial");
    for e in [200.0, 400.0, 800.0, 1000.0, 1200.0, 1400.0, 1600.0, 1800.0, 2000.0, 2200.0, 2600.0, 4000.0] {
        let mut frames = Vec::new();
        for i in 0..3 {
            let phase = i as f64 / 3.0;
            frames.push(synthetic_profile(e, f, phase, 0.3, line, rows));
        }
        let t = temporal_banding(&frames);
        let s = banding_amplitude(&frames[0]);
        println!("{e:>9.1} {:>7.3} {:>12.6} {:>12.6}", e * f / 1e6, t, s);
    }
}
