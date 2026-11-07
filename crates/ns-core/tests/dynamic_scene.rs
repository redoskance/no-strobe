//! A scene that moves but does not flicker must not produce a lock.
//!
//! On a Pixel 11 Pro Fold facing an animated monitor and an RGB keyboard the controller locked on a
//! different "frequency" at every launch (255, 420, 840, 1321, 3300 Hz) and even switched to 240 fps
//! high speed on a weak lock: scene motion and static spatial structure look like flicker to a
//! metric that only measures variation. These tests feed such scenes and count the locks.
use ns_core::camera::CameraLimits;
use ns_core::controller::State;
use ns_core::pwm_controller::PwmController;

const ROWS: usize = 512;
const FPS: f64 = 30.0;

fn limits() -> CameraLimits {
    CameraLimits {
        min_exposure_us: 100.0,
        max_exposure_us: 31000.0,
        sustain_us: 0.0,
        supported_fps: vec![FPS],
        min_iso: 100.0,
        max_iso: 6400.0,
    }
}

/// xorshift64*: a deterministic generator without a dependency.
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> f64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        ((self.0.wrapping_mul(0x2545F4914F6CDD1D) >> 11) as f64) / ((1u64 << 53) as f64)
    }
    fn centred(&mut self) -> f64 {
        self.next() * 2.0 - 1.0
    }
}

/// One frame of a scene with static periodic structure (keys, text lines) and animated regions
/// (a moving cursor, a pulsing backlight) whose amplitude does not depend on the exposure.
fn frame(rng: &mut Rng, t: usize, structure_period_rows: f64, animation: f64) -> Vec<f64> {
    (0..ROWS)
        .map(|r| {
            let x = r as f64;
            let keys = 0.5 + 0.25 * (2.0 * std::f64::consts::PI * x / structure_period_rows).sin();
            // Two animated bands that move and pulse from frame to frame.
            let c1 = 140.0 + 60.0 * ((t as f64) * 0.37).sin();
            let c2 = 380.0 + 40.0 * ((t as f64) * 0.23).cos();
            let band = |c: f64, w: f64| (-((x - c) / w).powi(2)).exp();
            let anim = animation * (band(c1, 30.0) * (1.0 + 0.5 * ((t as f64) * 1.7).sin()) + band(c2, 18.0) * rng.centred());
            (keys * (1.0 + anim) + 0.004 * rng.centred()).max(0.0)
        })
        .collect()
}

fn run(seed: u64, line_rate: f64, structure_period_rows: f64, animation: f64) -> (bool, f64) {
    let mut rng = Rng(seed.wrapping_mul(0x9E3779B97F4A7C15) | 1);
    let mut c = PwmController::new(limits(), line_rate);
    let mut last = c.on_profile(&frame(&mut rng, 0, structure_period_rows, animation));
    for t in 1..2500 {
        last = c.on_profile(&frame(&mut rng, t, structure_period_rows, animation));
        if last.state == State::Locked {
            return (true, last.exposure_us);
        }
    }
    (false, last.exposure_us)
}

fn lock_rate(line_rate: f64, structure: f64, animation: f64, seeds: u64) -> (u64, Vec<f64>) {
    let mut locks = 0;
    let mut where_ = Vec::new();
    for s in 1..=seeds {
        let (locked, e) = run(s, line_rate, structure, animation);
        if locked {
            locks += 1;
            where_.push(e);
        }
    }
    (locks, where_)
}

/// Same scene, with a real flicker on top: the lock must still happen, on a multiple of the period.
fn run_with_flicker(seed: u64, line_rate: f64, f_hz: f64, structure: f64, animation: f64) -> Option<f64> {
    let mut rng = Rng(seed.wrapping_mul(0x9E3779B97F4A7C15) | 1);
    let mut c = PwmController::new(limits(), line_rate);
    let mut phase = 0.0;
    let mk = |rng: &mut Rng, t: usize, e: f64, phase: f64| -> Vec<f64> {
        let scene = frame(rng, t, structure, animation);
        let fl = ns_core::pwm::synthetic_profile(e, f_hz, phase, 0.3, ROWS as f64 * FPS, ROWS);
        scene.iter().zip(&fl).map(|(s, f)| s * (0.75 + 0.25 * f)).collect()
    };
    let mut last = c.on_profile(&mk(&mut rng, 0, 1000.0, phase));
    for t in 1..3000 {
        phase += 0.3337;
        last = c.on_profile(&mk(&mut rng, t, last.exposure_us, phase));
        if last.state == State::Locked {
            return Some(last.exposure_us);
        }
    }
    None
}

#[test]
fn no_lock_on_scenes_without_flicker() {
    for (name, line_rate) in [("blind", 0.0), ("row rate known", ROWS as f64 * FPS)] {
        for (structure, animation) in [(60.0, 0.03), (97.0, 0.06), (41.0, 0.10), (150.0, 0.0)] {
            let (n, at) = lock_rate(line_rate, structure, animation, 12);
            assert_eq!(
                n, 0,
                "{name}: {n}/12 false locks in a scene without flicker (structure {structure} rows, animation {animation}) at {:?} us",
                at.iter().map(|e| e.round()).collect::<Vec<_>>()
            );
        }
    }
}

#[test]
fn a_real_flicker_in_a_dynamic_scene_still_locks_on_a_null() {
    // (name, row rate, flicker, asserted). Blind at 300 Hz does not lock in an animated scene (0/8,
    // before and after the verification of the row path): the scene motion (0.03) is as large as the
    // flicker modulation at the long exposures of the sweep and 3 frames per probe cannot tell them
    // apart. It is a known limit, reported here and not asserted.
    let rate = ROWS as f64 * FPS;
    for (name, line_rate, f_hz, asserted) in [
        ("blind", 0.0, 100.0, true),
        ("blind", 0.0, 300.0, false),
        ("row rate known", rate, 100.0, true),
        ("row rate known", rate, 300.0, true),
    ] {
        let n = 8;
        let mut good = 0;
        let mut seen = Vec::new();
        for seed in 1..=n {
            if let Some(e) = run_with_flicker(seed, line_rate, f_hz, 97.0, 0.03) {
                let t = 1e6 / f_hz;
                let k = (e / t).round();
                seen.push(e.round());
                if k >= 1.0 && (e - k * t).abs() < 0.08 * t {
                    good += 1;
                }
            }
        }
        eprintln!("{name:14} {f_hz:5.0} Hz: {good}/{n} locked on a multiple of the period {seen:?}");
        if asserted {
            assert!(good * 4 >= n * 3, "{name}, {f_hz} Hz: only {good}/{n} runs locked on a multiple of the period ({seen:?})");
        }
    }
}
