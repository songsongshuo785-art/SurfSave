use adblock::Engine;
use adblock::request::Request;
use std::env;
use std::fs;
use std::process;
use std::time::Instant;

const DEFAULT_ITERATIONS: usize = 20_000;
const WARMUP_ITERATIONS: usize = 1_000;
const P95_LIMIT_NANOS: u64 = 1_000_000;

fn main() {
    let arguments: Vec<String> = env::args().collect();
    if !(2..=3).contains(&arguments.len()) {
        eprintln!("usage: benchmark_engine <serialized-engine> [iterations]");
        process::exit(2);
    }
    let iterations = arguments
        .get(2)
        .map(|value| value.parse::<usize>())
        .transpose()
        .unwrap_or_else(|_| {
            eprintln!("iterations must be a positive integer");
            process::exit(2);
        })
        .unwrap_or(DEFAULT_ITERATIONS);
    if iterations == 0 {
        eprintln!("iterations must be greater than zero");
        process::exit(2);
    }

    let serialized = fs::read(&arguments[1]).unwrap_or_else(|error| {
        eprintln!("failed to read serialized engine: {error}");
        process::exit(2);
    });
    let initialize_started = Instant::now();
    let mut engine = Engine::default();
    engine.deserialize(&serialized).unwrap_or_else(|error| {
        eprintln!("failed to deserialize engine: {error}");
        process::exit(2);
    });
    let initialize_millis = initialize_started.elapsed().as_millis();

    for index in 0..WARMUP_ITERATIONS {
        evaluate_sample(&engine, index);
    }
    let mut samples = Vec::with_capacity(iterations);
    for index in 0..iterations {
        let started = Instant::now();
        evaluate_sample(&engine, index);
        samples.push(started.elapsed().as_nanos().min(u64::MAX as u128) as u64);
    }
    samples.sort_unstable();
    let p95_index = ((samples.len() * 95).div_ceil(100)).saturating_sub(1);
    let p95_nanos = samples[p95_index];
    let average_nanos = samples.iter().sum::<u64>() / samples.len() as u64;
    let maximum_nanos = *samples.last().unwrap_or(&0);

    println!(
        "{{\"iterations\":{iterations},\"snapshotBytes\":{},\"initializeMillis\":{initialize_millis},\"averageNanos\":{average_nanos},\"p95Nanos\":{p95_nanos},\"maximumNanos\":{maximum_nanos},\"p95LimitNanos\":{P95_LIMIT_NANOS}}}",
        serialized.len()
    );
    if p95_nanos > P95_LIMIT_NANOS {
        eprintln!("content-block engine P95 exceeded one millisecond");
        process::exit(1);
    }
}

fn evaluate_sample(engine: &Engine, index: usize) {
    let (url, source_url, request_type) = match index % 4 {
        0 => (
            "https://trafficjunky.com/preroll/video.m3u8",
            "https://video.example/watch",
            "media",
        ),
        1 => (
            "https://cdn.example/video/master.m3u8",
            "https://video.example/watch",
            "media",
        ),
        2 => (
            "https://doubleclick.net/pagead/script.js",
            "https://news.example/article",
            "script",
        ),
        _ => (
            "https://static.example/application.js",
            "https://news.example/article",
            "script",
        ),
    };
    let request = Request::new(url, source_url, request_type, "get")
        .expect("static benchmark request must be valid");
    let _ = engine.check_network_request(&request).should_block();
}
