package ch.k42.metropolis.grid.urbanGrid.forest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Exports the exact production block-state writes for a real Minecraft schematic preview. */
public final class MotherWillowVoxelPreview {
    public static void main(String[] args) throws IOException {
        Path output=Path.of(args.length>0?args[0]:"qa-out/mother-willow-preview.csv");
        Map<String,String> blocks=new TreeMap<>();
        MotherWillowGeometry.Terrain terrain=new MotherWillowGeometry.Terrain() {
            public int groundAt(int x,int z) { return 64; }
            public boolean allows(int x,int z) { return true; }
        };
        MotherWillowGeometry.grow((x,y,z,state)->blocks.put(x+","+y+","+z,state),
                terrain,0,0,0x4D4F544845525749L);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try(BufferedWriter writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8)) {
            writer.write("x,y,z,state\n");
            for(Map.Entry<String,String> entry:blocks.entrySet()) {
                writer.write(entry.getKey());
                writer.write(",");
                writer.write(entry.getValue());
                writer.write("\n");
            }
        }
        System.out.println("MotherWillowVoxelPreview PASS: "+blocks.size()+" exact emitted Minecraft block states");
    }
}