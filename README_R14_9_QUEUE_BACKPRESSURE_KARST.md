# Metropolis R14.9 DEV

Development build covering:
- Durable disk-backed deferred chunk jobs with bounded ready-work memory, atomic retry sidecars, self-rescheduling drain tasks, and a one-second drain watchdog.
- Chunky backpressure recovery that can resume at a stable ~80% retained heap (subject to queue <=128 and at least 512 MiB / 8% free headroom), then remains in the <=82% stable run band. The hard 86% pause and 1,024-job queue high-water mark remain active.
- Elevated karst mountain massif with broad uplands and deep inner cuts returning to the normal build-height datum.
- Open karst landbridge undersides, preserving the natural ravine floor and floor ecology.
- Preserved R14.8 Morphomos, Bloomlands and completed-BUILD export changes.

See [QA/R14_9/README_R14_9_QUEUE_BACKPRESSURE_KARST.md](QA/R14_9/README_R14_9_QUEUE_BACKPRESSURE_KARST.md) for implementation detail and the live-server acceptance tests.

This is a DEV build. CI and pure-function probes do not replace a live Paper 26.2 + Chunky sustained-generation test, and it is not certified for infinite generation.
