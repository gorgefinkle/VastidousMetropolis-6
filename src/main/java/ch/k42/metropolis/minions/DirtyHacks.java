package ch.k42.metropolis.minions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.block.Chest;

public class DirtyHacks {
   private DirtyHacks() {
   }

   public static void setChestName(Chest chest, String name) {
      try {
         chest.setCustomName(name);
         Minions.d("CHEST NAME SET TO: " + name + "; AT LOCATION: " + chest.getBlock().getLocation());
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   public static String getChestName(Chest chest) {
      String name = null;

      try {
         name = chest.getCustomName();
      } catch (Exception e) {
         e.printStackTrace();
      }

      return name == null ? "TEST" : name;
   }

   public static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
      try {
         return clazz.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
         Class<?> superClass = clazz.getSuperclass();
         if (superClass == null) {
            throw e;
         } else {
            return getField(superClass, fieldName);
         }
      }
   }

   public static Method getMethod(Class<?> clazz, String methodName, Class<?>... params) throws NoSuchMethodException {
      try {
         return clazz.getDeclaredMethod(methodName, params);
      } catch (NoSuchMethodException e) {
         Class<?> superClass = clazz.getSuperclass();
         if (superClass == null) {
            throw e;
         } else {
            return getMethod(superClass, methodName, params);
         }
      }
   }
}
