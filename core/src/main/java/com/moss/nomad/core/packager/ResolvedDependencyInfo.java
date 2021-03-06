/**
 * Copyright (C) 2013, Moss Computing Inc.
 *
 * This file is part of nomad.
 *
 * nomad is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * nomad is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with nomad; see the file COPYING.  If not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 *
 * Linking this library statically or dynamically with other modules is
 * making a combined work based on this library.  Thus, the terms and
 * conditions of the GNU General Public License cover the whole
 * combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent
 * modules, and to copy and distribute the resulting executable under
 * terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that
 * module.  An independent module is a module which is not derived from
 * or based on this library.  If you modify this library, you may extend
 * this exception to your version of the library, but you are not
 * obligated to do so.  If you do not wish to do so, delete this
 * exception statement from your version.
 */
package com.moss.nomad.core.packager;

import java.io.File;

public class ResolvedDependencyInfo {

	private String groupId;
	private String artifactId;
	private String version;
	private String type;
	private String classifier;
	
	private File file;
	
	public ResolvedDependencyInfo(String groupId, String artifactId, String version, String type, String classifier, File file) {
		
		if (groupId == null) {
			throw new NullPointerException();
		}
		
		if (artifactId == null) {
			throw new NullPointerException();
		}
		
		if (version == null) {
			throw new NullPointerException();
		}
		
		if (type == null) {
			type = "jar";
		}
		
		if (file == null) {
			throw new NullPointerException();
		}
		
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.version = version;
		this.type = type;
		this.classifier = classifier;
		this.file = file;
	}

	public String groupId() {
		return groupId;
	}

	public String artifactId() {
		return artifactId;
	}

	public String version() {
		return version;
	}

	public String type() {
		return type;
	}

	public String classifier() {
		return classifier;
	}
	
	public String toString() {
		return groupId + ":" + artifactId + ":" + type + ":" + version + ":" + classifier;
	}
	
	public File file() {
		return file;
	}
	
	public boolean equals(Object o) {
		return
			o != null
			&&
			o instanceof ResolvedDependencyInfo
			&&
			((ResolvedDependencyInfo)o).toString().equals(toString());
	}
	
	public int hashCode() {
		return toString().hashCode();
	}
}
