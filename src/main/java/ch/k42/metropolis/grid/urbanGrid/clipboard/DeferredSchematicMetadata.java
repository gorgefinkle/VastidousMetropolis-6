package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.generator.MetropolisGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

/**
 * Restored R30ea deferred tile-entity metadata spool. This is the legacy baseline,
 * NOT the upcoming lossless bounded-work-queue redesign. All records are retained
 * on disk until their original completion path acknowledges them.
 */
public final class DeferredSchematicMetadata {
    private static final int MAGIC = 1297367602;
    private static final int MAX_FILE=2_000_000;
    private static final int WINDOW=96;
    private static final int SCANS_PER_TICK=48;
    private static final int COMPLETES_PER_TICK=8;
    private static final long BUDGET_NS=6_000_000L;
    private static final int SHARDS=256;
    private static final Map<UUID,Scope> scopes=new HashMap<>();
    private DeferredSchematicMetadata(){}

    public record Placement(String source,int baseX,int baseZ,int streetLevel,int blockY,
                            int[] tile,int[] head,int[] chest,int[] spawner,long[] halo){}
    private static final class Slot {
        final Path path;
        long nextTry;
        int failures;
        final Set<Long> tickets=new HashSet<>();
        Slot(Path path){this.path=path;}
    }
    static long key(int x,int z){return ((long)x<<32)^((long)z&0xffffffffL);}
    public static Set<Long> requiredChunks(List<int[]> coords){
        Set<Long> result=new HashSet<>();
        for(int[] cell:coords){
            if(cell==null||cell.length<2)continue;
            long minX=Math.floorDiv((long)cell[0]-2,16),maxX=Math.floorDiv((long)cell[0]+2,16);
            long minZ=Math.floorDiv((long)cell[1]-2,16),maxZ=Math.floorDiv((long)cell[1]+2,16);
            for(long x=minX;x<=maxX;x++)for(long z=minZ;z<=maxZ;z++){
                if(x>=Integer.MIN_VALUE&&x<=Integer.MAX_VALUE&&z>=Integer.MIN_VALUE&&z<=Integer.MAX_VALUE)
                    result.add(key((int)x,(int)z));
            }
        }
        return result;
    }
    public static void enqueue(MetropolisGenerator generator,Placement placement){
        World world=generator.getWorld();Plugin plugin=generator.getPlugin();
        if(world==null||plugin==null||!plugin.isEnabled())
            throw new IllegalStateException("Cannot persist schematic metadata outside active world");
        Scope scope=scopes.computeIfAbsent(world.getUID(),u->new Scope(world,plugin));
        scope.generator=generator;
        try{scope.store(placement);}
        catch(IOException e){throw new IllegalStateException("Cannot durably spool tile metadata: "+placement.source(),e);}
        scope.ensureTicker();
    }
    public static void resume(Plugin plugin){
        for(World world:Bukkit.getWorlds()){
            if(Files.isDirectory(root(plugin,world))){
                Scope scope=scopes.computeIfAbsent(world.getUID(),u->new Scope(world,plugin));
                scope.findGenerator();scope.ensureTicker();
            }
        }
    }
    public static void shutdownNow(){
        for(Scope scope:new ArrayList<>(scopes.values()))scope.shutdown();
        scopes.clear();
    }
    private static Path root(Plugin plugin,World world){return plugin.getDataFolder().toPath().resolve("pending-tile-nbt").resolve(world.getUID().toString());}
    private static byte[] encode(Placement p)throws IOException{
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(raw)){
            out.writeInt(MAGIC);out.writeUTF(p.source());
            out.writeInt(p.baseX());out.writeInt(p.baseZ());out.writeInt(p.streetLevel());out.writeInt(p.blockY());
            writeArray(out,p.tile());writeArray(out,p.head());writeArray(out,p.chest());writeArray(out,p.spawner());
            out.writeInt(p.halo().length);for(long id:p.halo())out.writeLong(id);
        }
        byte[] bytes=raw.toByteArray();CRC32 crc=new CRC32();crc.update(bytes);
        ByteArrayOutputStream framed=new ByteArrayOutputStream(bytes.length+12);
        try(DataOutputStream out=new DataOutputStream(framed)){
            out.writeInt(bytes.length);out.write(bytes);out.writeLong(crc.getValue());
        }
        if(framed.size()>MAX_FILE)throw new IOException("Metadata job exceeds max file size");
        return framed.toByteArray();
    }
    private static void writeArray(DataOutputStream out,int[] coords)throws IOException{
        if(coords.length%3!=0)throw new IOException("Metadata coordinate array not triples");
        out.writeInt(coords.length/3);for(int c:coords)out.writeInt(c);
    }
    private static int[] readArray(DataInputStream in)throws IOException{
        int count=in.readInt();
        if(count<0||count>100_000)throw new IOException("Invalid job element count: "+count);
        int[] result=new int[count*3];for(int i=0;i<result.length;i++)result[i]=in.readInt();return result;
    }
    private static Placement decode(byte[] data)throws IOException{
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(data))){
            int len=in.readInt();
            if(len<12||len>MAX_FILE-12||len!=data.length-12)throw new IOException("Invalid metadata job length");
            byte[] bytes=new byte[len];in.readFully(bytes);long expected=in.readLong();
            CRC32 crc=new CRC32();crc.update(bytes);
            if(crc.getValue()!=expected)throw new IOException("Corrupt metadata job checksum");
            try(DataInputStream payload=new DataInputStream(new ByteArrayInputStream(bytes))){
                if(payload.readInt()!=MAGIC)throw new IOException("Unsupported metadata spool version");
                String source=payload.readUTF();
                int baseX=payload.readInt(),baseZ=payload.readInt(),street=payload.readInt(),y=payload.readInt();
                int[] tile=readArray(payload),head=readArray(payload),chest=readArray(payload),spawner=readArray(payload);
                int n=payload.readInt();
                if(n<1||n>512)throw new IOException("Invalid chunk halo size "+n);
                long[] halo=new long[n];for(int i=0;i<n;i++)halo[i]=payload.readLong();
                if(payload.available()!=0)throw new IOException("Trailing metadata bytes");
                return new Placement(source,baseX,baseZ,street,y,tile,head,chest,spawner,halo);
            }
        }
    }
    private static String fingerprint(Placement p){
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            String subject=p.source()+"|"+p.baseX()+"|"+p.baseZ()+"|"+p.streetLevel()+"|"+p.blockY()
                    +"|"+Arrays.hashCode(p.tile())+"|"+Arrays.hashCode(p.head())
                    +"|"+Arrays.hashCode(p.chest())+"|"+Arrays.hashCode(p.spawner());
            byte[] digest=hash.digest(subject.getBytes(StandardCharsets.UTF_8));
            StringBuilder str=new StringBuilder(32);
            for(int i=0;i<16;i++)str.append(String.format(Locale.ROOT,"%02x",digest[i]&255));
            return str.toString();
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }

    private static final class Scope implements Runnable {
        final World world;
        final Plugin plugin;
        final Path base;
        final ArrayDeque<Slot> window=new ArrayDeque<>();
        final Set<Path> inWindow=new HashSet<>();
        BukkitTask ticker;
        MetropolisGenerator generator;
        DirectoryStream<Path> listing;
        Iterator<Path> iterator;
        int nextShard;
        long ticks,lastLog;
        boolean loading;
        Slot staging;
        long stagingSince;
        int successesSinceLog;
        long createdSinceLog,scannedInCurrentSweep,observedPendingLastSweep,lastMissingSourceWarn;
        Scope(World world,Plugin plugin){
            this.world=world;this.plugin=plugin;this.base=root(plugin,world);recoverTempRecords();
        }
        private void recoverTempRecords(){
            if(!Files.isDirectory(base))return;
            for(int shard=0;shard<SHARDS;shard++){
                Path folder=base.resolve(String.format(Locale.ROOT,"%02x",shard));
                if(!Files.isDirectory(folder))continue;
                try(DirectoryStream<Path> temps=Files.newDirectoryStream(folder,".metadata-*.tmp")){
                    for(Path temp:temps){
                        try{
                            if(Files.size(temp)>MAX_FILE)continue;
                            Placement p=decode(Files.readAllBytes(temp));
                            Path target=folder.resolve(fingerprint(p)+".job");
                            if(Files.exists(target))Files.delete(temp);else Files.move(temp,target);
                        }catch(IOException ex){plugin.getLogger().warning("Deferred metadata temporary recovery failed: "+temp);}
                    }
                }catch(IOException ex){plugin.getLogger().warning("Deferred metadata recovery scan failed: "+ex);}
            }
        }
        void findGenerator(){
            if(generator==null){ChunkGenerator base=world.getGenerator();if(base instanceof MetropolisGenerator mg)generator=mg;}
        }
        void ensureTicker(){
            if(ticker==null&&plugin.isEnabled())ticker=Bukkit.getScheduler().runTaskTimer(plugin,this,1L,1L);
        }
        void store(Placement p)throws IOException{
            String id=fingerprint(p);Path shard=base.resolve(id.substring(0,2));Files.createDirectories(shard);
            Path target=shard.resolve(id+".job");if(Files.exists(target))return;
            byte[] data=encode(p);
            Path tmp=Files.createTempFile(shard,".metadata-",".tmp");
            try{
                Files.write(tmp,data,StandardOpenOption.TRUNCATE_EXISTING);
                try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException ex){Files.move(tmp,target);}
                catch(FileAlreadyExistsException ignored){}
            }finally{Files.deleteIfExists(tmp);}
            createdSinceLog++;
        }
        private void closeListing(){
            if(listing!=null)try{listing.close();}catch(IOException ignored){}
            listing=null;iterator=null;
        }
        private Path nextPath(){
            for(int tries=0;tries<SHARDS+1;tries++){
                if(iterator!=null&&iterator.hasNext()){
                    scannedInCurrentSweep++;return iterator.next();
                }
                closeListing();String prefix=String.format(Locale.ROOT,"%02x",nextShard);
                nextShard=(nextShard+1)%SHARDS;
                if(nextShard==0){observedPendingLastSweep=scannedInCurrentSweep;scannedInCurrentSweep=0;}
                Path folder=base.resolve(prefix);if(!Files.isDirectory(folder))continue;
                try{listing=Files.newDirectoryStream(folder,"*.job");iterator=listing.iterator();}
                catch(IOException ex){plugin.getLogger().warning("Deferred metadata scan failed: "+ex);closeListing();}
            }
            return null;
        }
        private void refill(){
            int scans=0;
            while(window.size()<WINDOW&&scans++<SCANS_PER_TICK){
                Path p=nextPath();if(p==null)break;
                if(inWindow.add(p))window.addLast(new Slot(p));
            }
        }
        private void requeueOrReturn(Slot slot){
            if(staging==slot)window.addLast(slot);
            else {release(slot);inWindow.remove(slot.path);}
        }
        private void release(Slot slot){
            for(long id:slot.tickets)try{world.removePluginChunkTicket((int)(id>>32),(int)id,plugin);}
            catch(RuntimeException ex){plugin.getLogger().warning("Could not release deferred metadata chunk ticket: "+ex);}
            slot.tickets.clear();if(staging==slot)staging=null;
        }
        private void loadLater(Slot slot,long id){
            if(loading||!plugin.isEnabled()||(staging!=null&&staging!=slot))return;
            staging=slot;stagingSince=ticks;loading=true;
            int x=(int)(id>>32),z=(int)id;
            CompletableFuture<Chunk> future;
            try{future=world.getChunkAtAsync(x,z,false);}
            catch(RuntimeException ex){loading=false;release(slot);slot.nextTry=ticks+100;return;}
            future.whenComplete((chunk,error)->{
                if(!plugin.isEnabled())return;
                Bukkit.getScheduler().runTask(plugin,()->{
                    loading=false;if(staging!=slot)return;
                    if(error!=null||chunk==null||!world.isChunkLoaded(x,z)){
                        release(slot);slot.nextTry=ticks+100;
                        if(error!=null&&++slot.failures%40==1)plugin.getLogger().warning("Deferred metadata load failed "+x+","+z+": "+error);
                    }else{
                        if(world.addPluginChunkTicket(x,z,plugin))slot.tickets.add(id);
                        else if(!world.isChunkLoaded(x,z)){release(slot);slot.nextTry=ticks+100;}
                    }
                });
            });
        }
        void shutdown(){
            if(ticker!=null){ticker.cancel();ticker=null;}
            closeListing();for(Slot s:window)release(s);
            window.clear();inWindow.clear();loading=false;
        }
        @Override public void run(){
            if(!plugin.isEnabled()){shutdown();return;}
            ticks++;findGenerator();if(generator==null)return;
            if(staging!=null&&ticks-stagingSince>100)release(staging);
            if(window.isEmpty()&&ticks%20!=1)return;
            refill();long started=System.nanoTime();int complete=0;
            int scanned=Math.min(window.size(),SCANS_PER_TICK);
            for(int i=0;i<scanned&&complete<COMPLETES_PER_TICK;i++){
                Slot slot=window.pollFirst();if(slot==null)break;
                if(!Files.isRegularFile(slot.path)){
                    inWindow.remove(slot.path);release(slot);continue;
                }
                if(ticks<slot.nextTry){requeueOrReturn(slot);continue;}
                try{
                    if(Files.size(slot.path)>MAX_FILE)throw new IOException("Oversized metadata job file");
                    Placement job=decode(Files.readAllBytes(slot.path));
                    FileClipboard source=FileClipboard.deferredSource(job.source());
                    if(source==null){
                        if(ticks-lastMissingSourceWarn>400){
                            plugin.getLogger().warning("Deferred metadata source unavailable: "+job.source());
                            lastMissingSourceWarn=ticks;
                        }
                        slot.nextTry=ticks+200;requeueOrReturn(slot);continue;
                    }
                    long missing=Long.MIN_VALUE;
                    for(long id:job.halo()){
                        if(!world.isChunkLoaded((int)(id>>32),(int)id)){missing=id;break;}
                    }
                    if(missing!=Long.MIN_VALUE){
                        loadLater(slot,missing);slot.nextTry=ticks+2;requeueOrReturn(slot);continue;
                    }
                    Set<Long> acquired=new HashSet<>();boolean okay=true;
                    try{
                        for(long id:job.halo()){
                            if(slot.tickets.contains(id))continue;
                            int x=(int)(id>>32),z=(int)id;
                            if(!world.isChunkLoaded(x,z)){okay=false;break;}
                            if(world.addPluginChunkTicket(x,z,plugin))acquired.add(id);
                            else if(!world.isChunkLoaded(x,z)){okay=false;break;}
                        }
                        if(!okay){
                            slot.nextTry=ticks+20;requeueOrReturn(slot);continue;
                        }
                        source.restoreDeferredMetadata(generator,job);
                        Files.delete(slot.path);
                        release(slot);inWindow.remove(slot.path);
                        complete++;successesSinceLog++;
                    }finally{
                        for(long id:acquired)try{world.removePluginChunkTicket((int)(id>>32),(int)id,plugin);}
                        catch(RuntimeException ex){plugin.getLogger().warning("Could not release deferred metadata chunk ticket: "+ex);}
                    }
                }catch(IOException ex){
                    release(slot);
                    if(++slot.failures%200==1)plugin.getLogger().severe("Deferred metadata job failure: "+slot.path+" "+ex);
                    slot.nextTry=ticks+200;requeueOrReturn(slot);
                }catch(RuntimeException ex){
                    release(slot);
                    if(++slot.failures%40==1)plugin.getLogger().warning("Deferred metadata job retry: "+ex);
                    slot.nextTry=ticks+100;requeueOrReturn(slot);
                }
                if(System.nanoTime()-started>BUDGET_NS)break;
            }
            if(ticks-lastLog>=400){
                if(!window.isEmpty()||successesSinceLog>0)plugin.getLogger().info("Deferred metadata queue: window="+window.size()+" created="+createdSinceLog+" complete="+successesSinceLog+" pending="+observedPendingLastSweep);
                lastLog=ticks;successesSinceLog=0;createdSinceLog=0;
            }
        }
    }
}
