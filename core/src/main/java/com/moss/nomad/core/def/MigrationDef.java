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
package com.moss.nomad.core.def;

import javax.xml.bind.annotation.XmlAttribute;

public final class MigrationDef {

	@XmlAttribute(required=true)
	private String groupId;
	
	@XmlAttribute(required=true)
	private String artifactId;
	
	@XmlAttribute(required=true)
	private String version;
	
	@XmlAttribute(required=true)
	private String type = "jar";
	
	@XmlAttribute
	private String classifier;
	
	MigrationDef() {}

	public MigrationDef(String groupId, String artifactId, String version, String type, String classifier) {
		
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
			throw new NullPointerException();
		}
		
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.version = version;
		this.type = type;
		this.classifier = classifier;
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
	
	public boolean equals(Object o) {
		return
			o != null
			&&
			o instanceof MigrationDef
			&&
			((MigrationDef)o).toString().equals(toString());
	}
	
	public int hashCode() {
		return toString().hashCode();
	}
}
