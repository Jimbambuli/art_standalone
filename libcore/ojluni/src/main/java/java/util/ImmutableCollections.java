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
}
