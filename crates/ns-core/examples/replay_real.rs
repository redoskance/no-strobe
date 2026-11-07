//! Replay REAL row profiles through the PWM controller.
//!
//!     cargo run --release -p ns-core --example replay_real profiles.csv [line_rate_hz|0] [frames] [floor_us]
//!
//! `profiles.csv` is what the app writes with `--ez dump_profiles true` (one line per frame:
//! exposure_us,timestamp_ns,row0,row1,...). The recorded exposures are a grid (12 by default, or the
//! `--es dump_exposures` list): the controller asks for any exposure, the recorded one nearest in log
//! scale answers with its next frame (the recorded frames of one exposure are consecutive, so their
//! phases against the flicker are real). A coarse stand-in for the live loop, but with the real light,
//! the real sensor noise and the real scene.
use ns_core::camera::CameraLimits;
use ns_core::controller::State;
use ns_core::pwm_controller::PwmController;
use std::collections::BTreeMap;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let path = args.get(1).expect("profiles.csv");
    let line_rate: f64 = args.get(2).and_then(|v| v.parse().ok()).unwrap_or(0.0);
    let frames: usize = args.get(3).and_then(|v| v.parse().ok()).unwrap_or(3000);
    let floor: f64 = args.get(4).and_then(|v| v.parse().ok()).unwrap_or(0.0);
    let text = std::fs::read_to_string(path).expect("read csv");
    let mut by: BTreeMap<u64, Vec<Vec<f64>>> = BTreeMap::new();
    for line in text.lines() {
        let mut it = line.split(',');
        let e: f64 = match it.next().and_then(|v| v.parse().ok()) {
            Some(v) => v,
            None => continue,
        };
        it.next();
        let prof: Vec<f64> = it.filter_map(|v| v.parse().ok()).collect();
        if prof.len() > 16 {
            by.entry(e as u64).or_default().push(prof);
        }
    }
    let exps: Vec<u64> = by.keys().copied().collect();
    let mut idx: BTreeMap<u64, usize> = BTreeMap::new();
    // The camera cannot ask for what was not recorded: the limits are the recorded range.
    let lo = *exps.first().unwrap() as f64;
    let hi = *exps.last().unwrap() as f64;
    let limits = CameraLimits {
        min_exposure_us: lo,
        max_exposure_us: hi,
        sustain_us: 0.0,
        supported_fps: vec![30.0],
        min_iso: 75.0,
        max_iso: 9618.0,
    };
    let mut c = PwmController::new(limits, line_rate);
    if floor > 0.0 {
        c.set_min_exposure(floor);
    }
    let mut e = 1000.0f64;
    let mut first_lock = None;
    let mut locked_frames = 0usize;
    let mut transitions = 0usize;
    let mut prev = State::Search;
    let mut last_e = 0.0;
    for i in 0..frames {
        let nearest = *exps
            .iter()
            .min_by(|a, b| ((**a as f64).ln() - e.ln()).abs().partial_cmp(&((**b as f64).ln() - e.ln()).abs()).unwrap())
            .unwrap();
        let k = idx.entry(nearest).or_insert(0);
        let frs = &by[&nearest];
        let prof = &frs[*k % frs.len()];
        *k += 1;
        let cmd = c.on_profile(prof);
        e = cmd.exposure_us;
        if cmd.state == State::Locked {
            locked_frames += 1;
            last_e = e;
            if first_lock.is_none() {
                first_lock = Some((i, e, c.frequency_hz()));
            }
        }
        if cmd.state != prev {
            transitions += 1;
            prev = cmd.state;
        }
    }
    match first_lock {
        Some((i, e0, f)) => println!(
            "LOCKED first at frame {i} ({e0:.0} us, {f:.1} Hz); locked {locked_frames}/{frames} frames, {transitions} transitions, last locked exposure {last_e:.0} us (answered by {} us)",
            exps.iter().min_by_key(|x| ((**x as f64 - last_e).abs() * 1000.0) as u64).unwrap()
        ),
        None => println!("never locked in {frames} frames ({transitions} transitions)"),
    }
}
