# R14.17 — Branch Growth Timeout Shutdown Guard

R14.17 closes the shutdown lifecycle bug seen in server logs from R14.15.

A pending branch-growth build can wait for its parcel to become populated. Its 200-tick watchdog used to reschedule itself indefinitely while the parcel remained unpopulated. If a callback reached the scheduler after Metropolis was disabled, it attempted to register another task and emitted `IllegalPluginAccessException: Plugin attempted to register task while disabled`.

The timeout callback now exits if the plugin is disabled. Initial watchdog registration and repeat registration also require the exact build accumulator to remain pending and the plugin to remain enabled. The branch-growth drain refuses late enqueue requests after shutdown instead of recreating its ticker or throwing.

This change prevents the shutdown exception storm; it does not fix the separate queue-throughput/backlog problem.

R14.17 includes the R14.16 mushroom cap/stem correction and all inherited R14.15/R14.14/R14.13 world-generation changes.
