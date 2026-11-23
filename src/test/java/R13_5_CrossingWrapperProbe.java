import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
public final class R13_5_CrossingWrapperProbe {
 static int checks;
 static void expect(boolean expected, int p, WaterField.Kind k, int outlet) {
  checks++;
  boolean got=UrbanGrid.legitimateCreekFrontageRoad(p,k,outlet);
  if(got!=expected)throw new AssertionError("planned="+p+" water="+k+" outlet="+outlet+" => "+got+" expected "+expected);
 }
 public static void main(String[]args){
  for(int p:new int[]{WorldLayout.BLOCK,WorldLayout.ZONE,WorldLayout.NATURAL})
   for(WaterField.Kind k:WaterField.Kind.values())expect(false,p,k,0);
  for(int p:new int[]{WorldLayout.STREET,WorldLayout.LANE}) {
   expect(true,p,WaterField.Kind.NONE,0);
   expect(false,p,WaterField.Kind.OCEAN,0);
  }
  expect(false,WorldLayout.STREET,WaterField.Kind.CREEK,0);
  expect(false,WorldLayout.STREET,WaterField.Kind.RIVER,0);
  expect(true,WorldLayout.STREET,WaterField.Kind.CREEK,1);
  expect(true,WorldLayout.STREET,WaterField.Kind.CREEK,2);
  expect(false,WorldLayout.STREET,WaterField.Kind.RIVER,2);
  expect(true,WorldLayout.LANE,WaterField.Kind.CREEK,0);
  expect(true,WorldLayout.LANE,WaterField.Kind.RIVER,0);
  System.out.println("R13.5 crossing-wrapper eligibility PASS "+checks+" cases");
 }
}
