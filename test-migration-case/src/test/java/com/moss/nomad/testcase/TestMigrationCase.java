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
package com.moss.nomad.testcase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.UUID;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;

import junit.framework.Assert;

import org.junit.Test;

import com.moss.nomad.core.history.Migration;
import com.moss.nomad.core.history.MigrationHistory;
import com.moss.nomad.core.runner.RunListener;
import com.moss.nomad.core.runner.Runner;
import com.moss.nomad.testcase.data.HostId;
import com.moss.nomad.testcase.data.LocalStorageUnitInfo;
import com.moss.nomad.testcase.data.ServiceNodeEnvironment;
import com.moss.nomad.testcase.data.StorageUnitId;

public class TestMigrationCase {

	@Test
	public void testCase() throws Exception {
		
		File packageFile = null;
		for (File f : new File("target").listFiles()) {
			if (f.getName().startsWith("nomad-test-migration-case-nomad")) {
				packageFile = f;
				break;
			}
		}
		
		if (packageFile == null) {
			throw new RuntimeException("Can't find package file");
		}
		
		/*
		 * Construct an empty migration-history.xml file, and run the runner.
		 */
		
		final MigrationHistory history = new MigrationHistory();
		
		RunListener l = new RunListener() {
			
			public void preMigration(Migration migration) {
				history.add(migration);
			}

			public void postMigration(Migration migration) {}
			
			public void migrationFailure(Migration migration, Exception ex) {
				history.migrations().remove(migration);
			}
		};
		
		File e1 = new File("target", "ENV:" + UUID.randomUUID());
		
		Runner runner = new Runner(packageFile);
		runner.addListener(l);
		runner.run("default", history, env(e1));
		
		Assert.assertTrue(e1.exists());
		
		/*
		 * Re run to make sure nothing gets run twice.
		 */
		File e2 = new File("target", "ENV:" + UUID.randomUUID());
		
		runner.run("default", history, env(e2));
		
		Assert.assertTrue(!e2.exists());
	}
	
	private byte[] env(File file) throws Exception {
		
		HostId hostId = new HostId();
		
		LocalStorageUnitInfo unit = new LocalStorageUnitInfo();
		unit.setHostId(hostId);
		unit.setId(new StorageUnitId(UUID.randomUUID()));
		unit.setLocalPath(file.getAbsolutePath());
		unit.setName("default");
		
		ServiceNodeEnvironment env = new ServiceNodeEnvironment();
		env.add(unit);
		
		JAXBContext ctx = JAXBContext.newInstance(ServiceNodeEnvironment.class);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Marshaller m = ctx.createMarshaller();
		m.marshal(env, out);
		
		return out.toByteArray();
	}
}
