/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package java.util;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The unmodifiable collections behind {@link List#of}, {@link List#copyOf}, {@link Set#of} and
 * {@link Set#copyOf}, with the contract they have since Java 9 / Android 11: no element may be null
 * (also not asked for with contains() and the like), every modification throws
 * {@link UnsupportedOperationException}, {@link Set#of} rejects duplicates, and the iteration order
 * of a set is unspecified.
 */
class ImmutableCollections {
    private ImmutableCollections() { }

    static final int EXPAND_FACTOR = 2;

    // libcore android16 ImmutableCollections: salt randomizing map iteration order
    // (set once per VM; need not be truly random, Android sets the seed directly).
    private static final long SALT32L;
    private static final boolean REVERSE;
    static {
        long color = 0x243F_6A88_85A3_08D3L; // slice of pi
        long seed = System.nanoTime();
        SALT32L = (int)((color * seed) >> 16) & 0xFFFF_FFFFL;
        REVERSE = (SALT32L & 1) == 0;
    }

    static final MapN<?,?> EMPTY_MAP = new MapN<>();

    static UnsupportedOperationException uoe() {
        return new UnsupportedOperationException();
    }

    static final ListN<?> EMPTY_LIST = new ListN<>(new Object[0]);
    static final SetN<?> EMPTY_SET = new SetN<>(new Object[0]);

    @SuppressWarnings("unchecked")
    static <E> List<E> emptyList() {
        return (List<E>) EMPTY_LIST;
    }

    @SuppressWarnings("unchecked")
    static <E> Set<E> emptySet() {
        return (Set<E>) EMPTY_SET;
    }

    /** Copies the array and checks it for null elements; a null array is a NullPointerException. */
    static <E> E[] checked(E[] elements) {
        E[] copy = elements.clone();
        for (E e : copy) {
            Objects.requireNonNull(e);
        }
        return copy;
    }

    static abstract class AbstractImmutableCollection<E> extends AbstractCollection<E> {
        @Override public boolean add(E e) { throw uoe(); }
        @Override public boolean addAll(Collection<? extends E> c) { throw uoe(); }
        @Override public void clear() { throw uoe(); }
        @Override public boolean remove(Object o) { throw uoe(); }
        @Override public boolean removeAll(Collection<?> c) { throw uoe(); }
        @Override public boolean removeIf(Predicate<? super E> filter) { throw uoe(); }
        @Override public boolean retainAll(Collection<?> c) { throw uoe(); }
    }

    static final class ListN<E> extends AbstractImmutableCollection<E> implements List<E>, RandomAccess {
        private final E[] elements;

        // the array is owned by this list: callers pass a copy
        ListN(E[] elements) {
            this.elements = elements;
        }

        @Override public int size() { return elements.length; }

        @Override public boolean isEmpty() { return elements.length == 0; }

        @Override
        public E get(int index) {
            return elements[index]; // ArrayIndexOutOfBoundsException is an IndexOutOfBoundsException
        }

        @Override public void add(int index, E element) { throw uoe(); }
        @Override public boolean addAll(int index, Collection<? extends E> c) { throw uoe(); }
        @Override public E remove(int index) { throw uoe(); }
        @Override public void replaceAll(UnaryOperator<E> operator) { throw uoe(); }
        @Override public E set(int index, E element) { throw uoe(); }
        @Override public void sort(Comparator<? super E> c) { throw uoe(); }

        @Override
        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }

        @Override
        public int indexOf(Object o) {
            Objects.requireNonNull(o);
            for (int i = 0; i < elements.length; i++) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            Objects.requireNonNull(o);
            for (int i = elements.length - 1; i >= 0; i--) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Iterator<E> iterator() {
            return listIterator(0);
        }

        @Override
        public ListIterator<E> listIterator() {
            return listIterator(0);
        }

        @Override
        public ListIterator<E> listIterator(final int index) {
            if (index < 0 || index > elements.length) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return new ListIterator<E>() {
                private int cursor = index;

                @Override public boolean hasNext() { return cursor < elements.length; }

                @Override
                public E next() {
                    if (cursor >= elements.length) {
                        throw new NoSuchElementException();
                    }
                    return elements[cursor++];
                }

                @Override public boolean hasPrevious() { return cursor > 0; }

                @Override
                public E previous() {
                    if (cursor <= 0) {
                        throw new NoSuchElementException();
                    }
                    return elements[--cursor];
                }

                @Override public int nextIndex() { return cursor; }
                @Override public int previousIndex() { return cursor - 1; }
                @Override public void remove() { throw uoe(); }
                @Override public void set(E e) { throw uoe(); }
                @Override public void add(E e) { throw uoe(); }
            };
        }

        @Override
        public List<E> subList(int fromIndex, int toIndex) {
            if (fromIndex < 0) {
                throw new IndexOutOfBoundsException("fromIndex = " + fromIndex);
            }
            if (toIndex > elements.length) {
                throw new IndexOutOfBoundsException("toIndex = " + toIndex);
            }
            if (fromIndex > toIndex) {
                throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
            }
            // the elements cannot change, so a copy behaves as the view would
            return new ListN<>(Arrays.copyOfRange(elements, fromIndex, toIndex));
        }

        @Override
        public Object[] toArray() {
            return Arrays.copyOf(elements, elements.length, Object[].class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            if (a.length < elements.length) {
                return (T[]) Arrays.copyOf(elements, elements.length, a.getClass());
            }
            System.arraycopy(elements, 0, a, 0, elements.length);
            if (a.length > elements.length) {
                a[elements.length] = null;
            }
            return a;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof List)) {
                return false;
            }
            Iterator<?> it = ((List<?>) o).iterator();
            for (E e : elements) {
                if (!it.hasNext() || !e.equals(it.next())) {
                    return false;
                }
            }
            return !it.hasNext();
        }

        @Override
        public int hashCode() {
            int h = 1;
            for (E e : elements) {
                h = 31 * h + e.hashCode();
            }
            return h;
        }
    }

    static final class SetN<E> extends AbstractImmutableCollection<E> implements Set<E> {
        private final LinkedHashSet<E> set;

        // the elements are checked for null by the caller; a duplicate is an IllegalArgumentException
        SetN(E[] elements) {
            set = new LinkedHashSet<>();
            for (E e : elements) {
                if (!set.add(Objects.requireNonNull(e))) {
                    throw new IllegalArgumentException("duplicate element: " + e);
                }
            }
        }

        /** The elements of a collection without duplicates, for {@link Set#copyOf}. */
        SetN(Collection<? extends E> elements) {
            set = new LinkedHashSet<>();
            for (E e : elements) {
                set.add(Objects.requireNonNull(e));
            }
        }

        @Override public int size() { return set.size(); }

        @Override public boolean isEmpty() { return set.isEmpty(); }

        @Override
        public boolean contains(Object o) {
            return set.contains(Objects.requireNonNull(o));
        }

        @Override
        public Iterator<E> iterator() {
            final Iterator<E> it = set.iterator();
            return new Iterator<E>() {
                @Override public boolean hasNext() { return it.hasNext(); }
                @Override public E next() { return it.next(); }
                @Override public void remove() { throw uoe(); }
            };
        }

        @Override
        public Object[] toArray() {
            return set.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return set.toArray(a);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Set)) {
                return false;
            }
            Set<?> other = (Set<?>) o;
            if (other.size() != size()) {
                return false;
            }
            try {
                return containsAll(other);
            } catch (ClassCastException | NullPointerException unused) {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return set.hashCode();
        }
    }

    // ---------- Map Implementations ----------

    // Not a jdk.internal.ValueBased class; disqualified by fields in superclass AbstractMap
    abstract static class AbstractImmutableMap<K,V> extends AbstractMap<K,V> {
        @Override public void clear() { throw uoe(); }
        @Override public V compute(K key, BiFunction<? super K,? super V,? extends V> rf) { throw uoe(); }
        @Override public V computeIfAbsent(K key, Function<? super K,? extends V> mf) { throw uoe(); }
        @Override public V computeIfPresent(K key, BiFunction<? super K,? super V,? extends V> rf) { throw uoe(); }
        @Override public V merge(K key, V value, BiFunction<? super V,? super V,? extends V> rf) { throw uoe(); }
        @Override public V put(K key, V value) { throw uoe(); }
        @Override public void putAll(Map<? extends K,? extends V> m) { throw uoe(); }
        @Override public V putIfAbsent(K key, V value) { throw uoe(); }
        @Override public V remove(Object key) { throw uoe(); }
        @Override public boolean remove(Object key, Object value) { throw uoe(); }
        @Override public V replace(K key, V value) { throw uoe(); }
        @Override public boolean replace(K key, V oldValue, V newValue) { throw uoe(); }
        @Override public void replaceAll(BiFunction<? super K,? super V,? extends V> f) { throw uoe(); }

        /**
         * @implNote {@code null} values are disallowed in these immutable maps,
         * so we can improve upon the default implementation since a
         * {@code null} return from {@code get(key)} always means the default
         * value should be returned.
         */
        @Override
        public V getOrDefault(Object key, V defaultValue) {
            V v;
            return ((v = get(key)) != null)
                    ? v
                    : defaultValue;
        }
    }

    // Not a jdk.internal.ValueBased class; disqualified by fields in superclass AbstractMap
    static final class Map1<K,V> extends AbstractImmutableMap<K,V> {
        private final K k0;
        private final V v0;

        Map1(K k0, V v0) {
            this.k0 = Objects.requireNonNull(k0);
            this.v0 = Objects.requireNonNull(v0);
        }

        @Override
        public Set<Map.Entry<K,V>> entrySet() {
            return Set.of(new KeyValueHolder<>(k0, v0));
        }

        @Override
        public V get(Object o) {
            return o.equals(k0) ? v0 : null; // implicit nullcheck of o
        }

        @Override
        public boolean containsKey(Object o) {
            return o.equals(k0); // implicit nullcheck of o
        }

        @Override
        public boolean containsValue(Object o) {
            return o.equals(v0); // implicit nullcheck of o
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int hashCode() {
            return k0.hashCode() ^ v0.hashCode();
        }
    }

    /**
     * An array-based Map implementation. There is a single array "table" that
     * contains keys and values interleaved: table[0] is kA, table[1] is vA,
     * table[2] is kB, table[3] is vB, etc. The table size must be even. It must
     * also be strictly larger than the size (the number of key-value pairs contained
     * in the map) so that at least one null key is always present.
     * @param <K> the key type
     * @param <V> the value type
     */
    // Not a jdk.internal.ValueBased class; disqualified by fields in superclass AbstractMap
    static final class MapN<K,V> extends AbstractImmutableMap<K,V> {

        final Object[] table; // pairs of key, value

        final int size; // number of pairs

        MapN(Object... input) {
            if ((input.length & 1) != 0) { // implicit nullcheck of input
                throw new InternalError("length is odd");
            }
            size = input.length >> 1;

            int len = EXPAND_FACTOR * input.length;
            len = (len + 1) & ~1; // ensure table is even length
            table = new Object[len];

            for (int i = 0; i < input.length; i += 2) {
                @SuppressWarnings("unchecked")
                    K k = Objects.requireNonNull((K)input[i]);
                @SuppressWarnings("unchecked")
                    V v = Objects.requireNonNull((V)input[i+1]);
                int idx = probe(k);
                if (idx >= 0) {
                    throw new IllegalArgumentException("duplicate key: " + k);
                } else {
                    int dest = -(idx + 1);
                    table[dest] = k;
                    table[dest+1] = v;
                }
            }
        }

        @Override
        public boolean containsKey(Object o) {
            Objects.requireNonNull(o);
            return size > 0 && probe(o) >= 0;
        }

        @Override
        public boolean containsValue(Object o) {
            Objects.requireNonNull(o);
            for (int i = 1; i < table.length; i += 2) {
                Object v = table[i];
                if (v != null && o.equals(v)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int hashCode() {
            int hash = 0;
            for (int i = 0; i < table.length; i += 2) {
                Object k = table[i];
                if (k != null) {
                    hash += k.hashCode() ^ table[i + 1].hashCode();
                }
            }
            return hash;
        }

        @Override
        @SuppressWarnings("unchecked")
        public V get(Object o) {
            if (size == 0) {
                Objects.requireNonNull(o);
                return null;
            }
            int i = probe(o);
            if (i >= 0) {
                return (V)table[i+1];
            } else {
                return null;
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        class MapNIterator implements Iterator<Map.Entry<K,V>> {

            private int remaining;

            private int idx;

            MapNIterator() {
                remaining = size;
                // pick an even starting index in the [0 .. table.length-1]
                // range randomly based on SALT32L
                idx = (int) ((SALT32L * (table.length >> 1)) >>> 32) << 1;
            }

            @Override
            public boolean hasNext() {
                return remaining > 0;
            }

            private int nextIndex() {
                int idx = this.idx;
                if (REVERSE) {
                    if ((idx += 2) >= table.length) {
                        idx = 0;
                    }
                } else {
                    if ((idx -= 2) < 0) {
                        idx = table.length - 2;
                    }
                }
                return this.idx = idx;
            }

            @Override
            public Map.Entry<K,V> next() {
                if (remaining > 0) {
                    int idx;
                    while (table[idx = nextIndex()] == null) {}
                    @SuppressWarnings("unchecked")
                    Map.Entry<K,V> e =
                            new KeyValueHolder<>((K)table[idx], (V)table[idx+1]);
                    remaining--;
                    return e;
                } else {
                    throw new NoSuchElementException();
                }
            }
        }

        @Override
        public Set<Map.Entry<K,V>> entrySet() {
            return new AbstractSet<Map.Entry<K,V>>() {
                @Override
                public int size() {
                    return MapN.this.size;
                }

                @Override
                public Iterator<Map.Entry<K,V>> iterator() {
                    return new MapNIterator();
                }

                // Android-added: AbstractCollection#clear implementation is no-op when
                // collection is empty. Overriding this method to make Map.of().clear()
                // and Map.of().entrySet().clear() behaviour equal.
                @Override
                public void clear() {
                    throw uoe();
                }
            };
        }

        // returns index at which the probe key is present; or if absent,
        // (-i - 1) where i is location where element should be inserted.
        // Callers are relying on this method to perform an implicit nullcheck
        // of pk.
        private int probe(Object pk) {
            int idx = Math.floorMod(pk.hashCode(), table.length >> 1) << 1;
            while (true) {
                @SuppressWarnings("unchecked")
                K ek = (K)table[idx];
                if (ek == null) {
                    return -idx - 1;
                } else if (pk.equals(ek)) {
                    return idx;
                } else if ((idx += 2) == table.length) {
                    idx = 0;
                }
            }
        }
    }
}
