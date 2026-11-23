package ch.k42.metropolis.minions;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps Paper's watchdog from killing the server while a world-plan tile is being computed.
 *
 * Planning a city tile runs inside populate(), on the server thread, and takes anywhere from a second to about a minute
 * (it was 17-58 s per city tile in a real 26.2 log). Paper's watchdog stops the server when the main thread has not
 * ticked for spigot.yml's settings.timeout-time (60 s by default), so a big enough tile is fatal -- and the watchdog
 * dumps every thread every 5 s on the way there, which is where thousands of ERROR lines in the log came from.
 *
 * WatchdogThread.tick() just stamps a volatile timestamp, so any thread may call it. While at least one plan is running
 * a daemon thread stamps it every few seconds. It is bounded: after MAX_MS it stops, so a plan that really never ends
 * is still caught by the watchdog. Nothing here touches plugin state, so it cannot race with the planner.
 *
 * The server still cannot tick while a tile is planned (players would see the freeze); this only stops it being killed.
 * Pre-generating with a high spigot.yml timeout-time is the same remedy by configuration.
 *
 * Tunable with -Dmetropolis.watchdog.periodMs=... and -Dmetropolis.watchdog.maxMs=... (and switched off with
 * -Dmetropolis.watchdog.keepAlive=false).
 */
public final class WatchdogKeepAlive {
   private static final long PERIOD_MS = Math.max(250L, Long.getLong("metropolis.watchdog.periodMs", 3000L));
   private static final long MAX_MS = Math.max(1000L, Long.getLong("metropolis.watchdog.maxMs", 15L * 60L * 1000L));
   private static final long LOG_AFTER_MS = 8000L;
   private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("metropolis.watchdog.keepAlive"));

   private static final AtomicInteger ACTIVE = new AtomicInteger();
   private static final AtomicBoolean UNAVAILABLE_NOTED = new AtomicBoolean();
   private static final Object LOCK = new Object();
   private static volatile Method tickMethod;
   private static volatile boolean unavailable;
   private static Thread worker;

   private WatchdogKeepAlive() {
   }

   /** A running plan. Close it (try-with-resources) when the plan is done; close() never throws. */
   public static final class Token implements AutoCloseable {
      private final String what;
      private final long started = System.nanoTime();
      private boolean closed;

      private Token(String what) {
         this.what = what;
      }

      @Override
      public void close() {
         if (this.closed) {
            return;
         }
         this.closed = true;
         ACTIVE.decrementAndGet();
         long ms = (System.nanoTime() - this.started) / 1_000_000L;
         if (ms >= LOG_AFTER_MS) {
            Minions.w("%s took %d ms on the server thread; Paper's watchdog was kept informed so it would not stop the server.", this.what, ms);
         }
      }
   }

   public static Token begin(String what) {
      Token token = new Token(what);
      ACTIVE.incrementAndGet();
      if (ENABLED) {
         ensureWorker();
      }
      return token;
   }

   private static void ensureWorker() {
      synchronized (LOCK) {
         if (worker != null && worker.isAlive()) {
            return;
         }
         worker = new Thread(WatchdogKeepAlive::run, "Metropolis-watchdog-keepalive");
         worker.setDaemon(true);
         worker.start();
      }
   }

   private static void run() {
      long started = System.nanoTime();
      while (ACTIVE.get() > 0) {
         if ((System.nanoTime() - started) / 1_000_000L > MAX_MS) {
            Minions.w("A plan has been running for over %d s: no longer keeping the watchdog informed.", MAX_MS / 1000L);
            return;
         }
         tick();
         try {
            Thread.sleep(PERIOD_MS);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
         }
      }
   }

   private static void tick() {
      if (unavailable) {
         return;
      }
      try {
         Method m = tickMethod;
         if (m == null) {
            m = Class.forName("org.spigotmc.WatchdogThread").getMethod("tick");
            tickMethod = m;
         }
         m.invoke(null);
      } catch (Throwable e) {
         unavailable = true;
         if (UNAVAILABLE_NOTED.compareAndSet(false, true)) {
            Minions.w("Could not reach Paper's watchdog (%s: %s). A tile plan that takes longer than spigot.yml settings.timeout-time "
                    + "(60 s by default) will stop the server: raise timeout-time while pre-generating.", e.getClass().getSimpleName(), e.getMessage());
         }
      }
   }
}
