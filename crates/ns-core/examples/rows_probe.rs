//! Why `estimate_period_rows` returns None on the synthetic profile the app sees.
//!     cargo run -q -p ns-core --example rows_probe [f_hz] [exposure_us] [rows] [line_hz]
use ns_core::pwm::{estimate_period_rows, synthetic_profile};

fn autocorr(x: &[f64], lag: usize) -> f64 {
    let n = x.len();
    let mut s = 0.0;
    for i in lag..n {
        s += x[i] * x[i - lag];
    }
    s
}

fn main() {
    let f: f64 = std::env::args().nth(1).and_then(|v| v.parse().ok()).unwrap_or(500.0);
    let e: f64 = std::env::args().nth(2).and_then(|v| v.parse().ok()).unwrap_or(200.0);
    let rows: usize = std::env::args().nth(3).and_then(|v| v.parse().ok()).unwrap_or(1080);
    let line: f64 = std::env::args().nth(4).and_then(|v| v.parse().ok()).unwrap_or(40_000.0);
    let frame_us = 1e6 / 30.0;
    let min_rows = rows as f64 * (1e6 / 5000.0) / frame_us;
    let max_rows = rows as f64 / 3.0;
    let prof = synthetic_profile(e, f, 0.0, 0.3, line, rows);
    let period_rows = line / f;
    let est = estimate_period_rows(&prof, min_rows, max_rows);
    println!(
        "f {f} Hz, exposure {e} us, {rows} rows, line {line} Hz -> period {period_rows:.1} rows; \
         min_rows {min_rows:.2}, max_rows {max_rows:.1}; estimate = {est:?}"
    );
    let mean = prof.iter().sum::<f64>() / rows as f64;
    let x: Vec<f64> = prof.iter().map(|v| v - mean).collect();
    let norm = x.iter().map(|v| v * v).sum::<f64>();
    println!("mean {mean:.5}, norm {norm:.5}, profile min {:.5} max {:.5}",
        prof.iter().min_by(|a, b| a.partial_cmp(b).unwrap()).unwrap(),
        prof.iter().max_by(|a, b| a.partial_cmp(b).unwrap()).unwrap());
    println!("lag  c(lag)   (normalized)");
    let lo = (min_rows.ceil() as usize).max(2);
    let hi = (max_rows.floor() as usize).min(rows / 2);
    let c: Vec<f64> = (lo..=hi).map(|lag| autocorr(&x, lag) / norm).collect();
    println!("lo {lo} hi {hi} c.len {}", c.len());
    let mut trough = None;
    for i in 1..c.len() - 1 {
        if c[i] < c[i - 1] && c[i] <= c[i + 1] {
            trough = Some(i);
            break;
        }
    }
    let start = match trough { Some(i) => i + 1, None => 1 };
    let mut peak = None;
    for i in start..c.len() - 1 {
        if c[i] > c[i - 1] && c[i] >= c[i + 1] {
            peak = Some(i);
            break;
        }
    }
    let idx = match peak { Some(i) => i, None => { println!("no peak in range"); return; } };
    let best_c = c[idx];
    let half = (idx + lo) / 2;
    let c_half = autocorr(&x, half) / norm;
    println!(
        "trough {:?} (lag {:?})  peak idx {idx} lag {}  best_c {best_c:.4}  half {half} c_half {c_half:.4} \
         limit {:.4} -> {}",
        trough.map(|i| i + lo),
        trough.map(|i| i + lo),
        idx + lo,
        -0.25 * best_c,
        if c_half > -0.25 * best_c { "REJECTED (half)" } else { "accepted" }
    );
    for i in 0..c.len().min(20) {
        println!("lag {:>4} c {:>8.4}", i + lo, c[i]);
    }
    let around = (idx.saturating_sub(6))..=(idx + 6).min(c.len() - 1);
    for i in around {
        println!("lag {:>4} c {:>8.4}", i + lo, c[i]);
    }
}
