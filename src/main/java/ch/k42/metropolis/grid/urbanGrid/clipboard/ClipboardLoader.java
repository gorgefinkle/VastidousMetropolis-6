package ch.k42.metropolis.grid.urbanGrid.clipboard;

import java.io.File;
import java.util.Map;

public interface ClipboardLoader {
   Map<String, Clipboard> loadSchematics(File var1, File var2);
}
