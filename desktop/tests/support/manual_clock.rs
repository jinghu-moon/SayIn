use std::{
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use voicelink_receiver::ledger::Clock;

pub struct ManualClock {
    origin: Instant,
    elapsed_millis: AtomicU64,
}

impl ManualClock {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            origin: Instant::now(),
            elapsed_millis: AtomicU64::new(0),
        })
    }

    pub fn advance(&self, duration: Duration) {
        self.elapsed_millis
            .fetch_add(duration.as_millis().try_into().unwrap(), Ordering::SeqCst);
    }
}

impl Clock for ManualClock {
    fn now(&self) -> Instant {
        self.origin + Duration::from_millis(self.elapsed_millis.load(Ordering::SeqCst))
    }

    fn unix_millis(&self) -> i64 {
        1_700_000_000_000 + self.elapsed_millis.load(Ordering::SeqCst) as i64
    }
}
