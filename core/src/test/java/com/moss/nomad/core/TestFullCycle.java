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
package com.moss.nomad.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import com.moss.maven.util.Pom;
import com.moss.maven.util.SimpleArtifact;
import com.moss.maven.util.SimpleArtifactFinder;
import com.moss.nomad.api.v1.Classpath;
import com.moss.nomad.api.v1.ClasspathEntry;
import com.moss.nomad.core.def.MigrationDef;
import com.moss.nomad.core.def.MigrationDefs;
import com.moss.nomad.core.history.Migration;
import com.moss.nomad.core.history.MigrationHistory;
import com.moss.nomad.core.packager.Packager;
import com.moss.nomad.core.packager.PackagerResolver;
import com.moss.nomad.core.packager.ResolvedDependencyInfo;
import com.moss.nomad.core.packager.ResolvedMigrationInfo;
import com.moss.nomad.core.runner.RunListener;
import com.moss.nomad.core.runner.Runner;
import com.moss.nomad.test_migration_support.v1.data.HostId;
import com.moss.nomad.test_migration_support.v1.data.LocalStorageUnitInfo;
import com.moss.nomad.test_migration_support.v1.data.ServiceNodeEnvironment;
import com.moss.nomad.test_migration_support.v1.data.StorageUnitId;

public class TestFullCycle {
	
	private JAXBContext ctx;
	private String version;
	
	@Before
	public void before() throws Exception {
//		BasicConfigurator.configure();
		ctx = JAXBContext.newInstance(ServiceNodeEnvironment.class, Classpath.class);

		Pom pom = new Pom(new File("pom.xml"));
		version = pom.getParent().getVersion();
	}
	
	@Test
	public void fullCycle() throws Exception {
		
		/*
		 * Step 1, build a migration-defs.xml incorporating the nomad-test-migration-handler.
		 * Could probably make use of the maven embedder here to ease compilation of the 
		 * test project.
		 */
		
		MigrationDef def = new MigrationDef(
			"com.moss.nomad", 
			"nomad-test-migration-handler", 
			version, 
			"jar", 
			null
		);
		
		MigrationDefs defs = new MigrationDefs();
		defs.add(def);
		
		/*
		 * Package up the migration definitions/handlers.
		 * Use a fake resolver since the one in MavenUtil
		 * uses the maven embedder and can't work offline.
		 */
		
		PackagerResolver res = new PackagerResolver() {
			public ResolvedMigrationInfo resolve(MigrationDef def) throws Exception {
				
				File handlerFile = TestFullCycle.this.resolve(def);
				
				Classpath cp;
				{
					System.out.println(handlerFile.getAbsolutePath());
					JarFile jar = new JarFile(handlerFile);
					JarEntry entry = new JarEntry("META-INF/classpath.xml");
					InputStream in = jar.getInputStream(entry);
					Unmarshaller u = ctx.createUnmarshaller();
					cp = (Classpath)u.unmarshal(in);
					in.close();
					jar.close();
				}
				
				List<ResolvedDependencyInfo> info = new ArrayList<ResolvedDependencyInfo>();
				for (ClasspathEntry e : cp.entries()) {
					ResolvedDependencyInfo i = new ResolvedDependencyInfo(
						e.groupId(),
						e.artifactId(),
						e.version(),
						e.type(),
						e.classifier(),
						TestFullCycle.this.resolve(e)
					);
					info.add(i);
				}
				
				return new ResolvedMigrationInfo(handlerFile, info);
			}
		};
		
		Packager packager = new Packager(res, -1);
		packager.add("default", defs.defs());
		
		File packageFile = new File("target/package-file.jar");
		
		OutputStream out = new FileOutputStream(packageFile);
		packager.write(out);
		out.close();
		
		Assert.assertTrue(packageFile.exists());
		
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
		
		File f1 = new File("target", "ENV:" + UUID.randomUUID());
		
		Runner runner = new Runner(packageFile);
		runner.addListener(l);
		runner.run("default", history, env(f1));
		
		Assert.assertTrue(f1.exists());
		
		/*
		 * Re run to make sure nothing gets run twice.
		 */
		File f2 = new File("target", "ENV:" + UUID.randomUUID());
		
		runner.run("default", history, env(f2));
		
		Assert.assertTrue(!f2.exists());
	}
	
	private File resolve(MigrationDef def) {
		return new SimpleArtifactFinder().findLocal(new SimpleArtifact(
			def.groupId(), 
			def.artifactId(), 
			def.version(), 
			def.classifier(), 
			def.type()
		));
	}
	
	private File resolve(ClasspathEntry e) {
		return new SimpleArtifactFinder().findLocal(new SimpleArtifact(
			e.groupId(), 
			e.artifactId(), 
			e.version(), 
			e.classifier(), 
			e.type()
		));
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
		
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Marshaller m = ctx.createMarshaller();
		m.marshal(env, out);
		
		return out.toByteArray();
	}
}
