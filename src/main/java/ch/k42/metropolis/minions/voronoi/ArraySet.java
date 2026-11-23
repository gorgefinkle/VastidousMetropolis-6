package ch.k42.metropolis.minions.voronoi;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

public class ArraySet<E> extends AbstractSet<E> {
   private ArrayList<E> items;

   public ArraySet() {
      this(3);
   }

   public ArraySet(int initialCapacity) {
      this.items = new ArrayList<>(initialCapacity);
   }

   public ArraySet(Collection<? extends E> collection) {
      this.items = new ArrayList<>(collection.size());

      for (E item : collection) {
         if (!this.items.contains(item)) {
            this.items.add(item);
         }
      }
   }

   public E get(int index) throws IndexOutOfBoundsException {
      return this.items.get(index);
   }

   public boolean containsAny(Collection<?> collection) {
      for (Object item : collection) {
         if (this.contains(item)) {
            return true;
         }
      }

      return false;
   }

   @Override
   public boolean add(E item) {
      return this.items.contains(item) ? false : this.items.add(item);
   }

   @Override
   public Iterator<E> iterator() {
      return this.items.iterator();
   }

   @Override
   public int size() {
      return this.items.size();
   }
}
