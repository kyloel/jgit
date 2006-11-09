/*
 *  Copyright (C) 2006  Shawn Pearce <spearce@spearce.org>
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License, version 2.1, as published by the Free Software Foundation.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 */
package org.spearce.jgit.lib;

import java.util.zip.Deflater;

public class CoreConfig
{
    private final int compression;

    private final boolean legacyHeaders;

    protected CoreConfig(final RepositoryConfig rc)
    {
        compression = rc.getInt(
            "core",
            "compression",
            Deflater.DEFAULT_COMPRESSION);
        legacyHeaders = rc.getBoolean("core", "legacyHeaders", false);
    }

    public int getCompression()
    {
        return compression;
    }

    public boolean useLegacyHeaders()
    {
        return legacyHeaders;
    }
}