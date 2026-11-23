Compile standalone disk queue test (JDK 25, no Bukkit):

  javac -d qa-classes src/main/java/ch/k42/metropolis/generator/DiskDeferredChunkQueue.java QA/R14_6/DiskDeferredChunkQueueProbe.java
  java -Xmx160m -cp qa-classes DiskDeferredChunkQueueProbe

Expected: PASS jobs=1200 restarts=2 retries=1 migrations=2 bounded-window=128 negative-coordinates=PASS no-loss=PASS fail-closed=PASS

Production compile uses the Maven pom and supplied Paper/WorldEdit APIs.
