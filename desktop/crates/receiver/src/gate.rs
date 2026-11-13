//! A pause is a generation fence: an older resume cannot reopen the gate.
use std::sync::atomic::{AtomicU64, Ordering};

pub struct InputGate(AtomicU64);

impl InputGate {
    pub(crate) fn new() -> Self {
        Self(AtomicU64::new(1))
    }

    pub fn is_paused(&self) -> bool {
        self.snapshot() & 1 != 0
    }

    pub(crate) fn snapshot(&self) -> u64 {
        self.0.load(Ordering::Acquire)
    }

    pub(crate) fn pause(&self) {
        // The low bit stores paused; every close advances the generation even
        // if already paused. Reaching 2^63 closes in one process is infeasible.
        self.0
            .try_update(Ordering::AcqRel, Ordering::Acquire, |value| {
                Some(value.wrapping_add(2) | 1)
            })
            .expect("unconditional update");
    }

    pub(crate) fn reopen(&self, expected: u64) -> bool {
        self.0
            .compare_exchange(expected, expected & !1, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn later_pause_fences_earlier_resume_even_if_already_paused() {
        let gate = InputGate::new();
        let old = gate.snapshot();
        gate.pause();
        assert!(!gate.reopen(old));
        assert!(gate.is_paused());
        assert!(gate.reopen(gate.snapshot()));
        assert!(!gate.is_paused());
        let old = gate.snapshot();
        gate.pause();
        assert!(!gate.reopen(old));
        assert!(gate.is_paused());
    }
}
