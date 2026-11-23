package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;

/** One entry of the in-memory schematic index -- see ClipboardDAO. A plain object; nothing is persisted. */
public class ClipboardBean {
   Integer id;
   String fileHash;
   String fileName;
   Direction direction;
   ContextType context;
   SchematicType schematicType;
   RoadType roadType;
   int size_x;
   int size_y;

   public Integer getId() {
      return this.id;
   }

   public void setId(Integer id) {
      this.id = id;
   }

   public SchematicType getSchematicType() {
      return this.schematicType;
   }

   public void setSchematicType(SchematicType schematicType) {
      this.schematicType = schematicType;
   }

   public String getFileHash() {
      return this.fileHash;
   }

   public void setFileHash(String fileHash) {
      this.fileHash = fileHash;
   }

   public Direction getDirection() {
      return this.direction;
   }

   public void setDirection(Direction direction) {
      this.direction = direction;
   }

   public ContextType getContext() {
      return this.context;
   }

   public void setContext(ContextType context) {
      this.context = context;
   }

   public int getSize_x() {
      return this.size_x;
   }

   public void setSize_x(int size_x) {
      this.size_x = size_x;
   }

   public int getSize_y() {
      return this.size_y;
   }

   public void setSize_y(int size_y) {
      this.size_y = size_y;
   }

   public void setSize(Cartesian2D size) {
      this.size_x = size.X;
      this.size_y = size.Y;
   }

   public Cartesian2D getSize() {
      return new Cartesian2D(this.size_x, this.size_y);
   }

   public String getFileName() {
      return this.fileName;
   }

   public void setFileName(String fileName) {
      this.fileName = fileName;
   }

   public RoadType getRoadType() {
      return this.roadType;
   }

   public void setRoadType(RoadType roadType) {
      this.roadType = roadType;
   }
}
