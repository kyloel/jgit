/*
 *  Copyright (C) 2008  Shawn Pearce <spearce@spearce.org>
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public
 *  License, version 2, as published by the Free Software Foundation.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 */
package org.spearce.jgit.revwalk;

import java.io.IOException;

import org.spearce.jgit.errors.CorruptObjectException;
import org.spearce.jgit.errors.IncorrectObjectTypeException;
import org.spearce.jgit.errors.MissingObjectException;
import org.spearce.jgit.errors.StopWalkException;
import org.spearce.jgit.revwalk.filter.RevFilter;

/**
 * Default (and first pass) RevCommit Generator implementation for RevWalk.
 * <p>
 * This generator starts from a set of one or more commits and process them in
 * descending (newest to oldest) commit time order. Commits automatically cause
 * their parents to be enqueued for further processing, allowing the entire
 * commit graph to be walked. A {@link RevFilter} may be used to select a subset
 * of the commits and return them to the caller.
 */
abstract class AbstractPendingGenerator extends Generator {
	protected final RevWalk walker;

	private final AbstractRevQueue pending;

	private final RevFilter filter;

	AbstractPendingGenerator(final RevWalk w, final AbstractRevQueue p,
			final RevFilter f) {
		walker = w;
		pending = p;
		filter = f;
	}

	@Override
	int outputType() {
		if (pending instanceof DateRevQueue)
			return SORT_COMMIT_TIME_DESC;
		return 0;
	}

	@Override
	RevCommit next() throws MissingObjectException,
			IncorrectObjectTypeException, IOException {
		try {
			for (;;) {
				final RevCommit c = pending.pop();
				if (c == null) {
					walker.curs.release();
					return null;
				}

				final boolean produce;
				if ((c.flags & RevWalk.UNINTERESTING) != 0)
					produce = false;
				else if (filter.include(walker, c))
					produce = include(c);
				else
					produce = false;

				final int carry = c.flags & RevWalk.CARRY_MASK;
				for (final RevCommit p : c.parents) {
					p.flags |= carry;
					if ((p.flags & RevWalk.SEEN) != 0)
						continue;
					if ((p.flags & RevWalk.PARSED) == 0)
						p.parse(walker);
					p.flags |= RevWalk.SEEN;
					pending.add(p);
				}

				if ((c.flags & RevWalk.UNINTERESTING) != 0) {
					if (pending.everbodyHasFlag(RevWalk.UNINTERESTING))
						throw StopWalkException.INSTANCE;
					c.dispose();
					continue;
				}

				if (produce)
					return c;
				else
					c.dispose();
			}
		} catch (StopWalkException swe) {
			walker.curs.release();
			pending.clear();
			return null;
		}
	}

	/**
	 * Determine if a commit should produce in the output.
	 * 
	 * @param c
	 *            the current commit.
	 * @return true if this commit should still be produced in the result (it
	 *         looked interesting); false if this commit should be omitted from
	 *         the result (it appeared boring).
	 * @throws MissingObjectException
	 *             see TreeWalk for reasons.
	 * @throws IncorrectObjectTypeException
	 *             see TreeWalk for reasons.
	 * @throws CorruptObjectException
	 *             see TreeWalk for reasons.
	 * @throws IOException
	 *             see TreeWalk for reasons.
	 */
	abstract boolean include(final RevCommit c) throws MissingObjectException,
			IncorrectObjectTypeException, IOException, CorruptObjectException;
}
