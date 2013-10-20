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
package com.moss.nomad.core.runner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.Instant;

import com.moss.nomad.core.def.MigrationDef;
import com.moss.nomad.core.history.Migration;
import com.moss.nomad.core.history.MigrationHistory;
import com.moss.nomad.core.packager.MigrationContainer;
import com.moss.nomad.core.packager.MigrationPackage;
import com.moss.nomad.core.packager.MigrationPath;
import com.moss.nomad.core.packager.MigrationResources;

public class Runner {

	/*
	 * Takes the following parameters
	 * - The migration history of the data against which we're running.
	 * - The environment parameter to be passed to migration handlers when executing them.
	 * - The jar containing the migration packages available.
	 * 
	 * Returns the following results
	 * - The modified migration history as a result of the work performed by the runner.
	 * 
	 * Determine the sequence of migrations that must be performed for the data to be completely up-to-date.
	 * If any migrations are required but not found in the package jar, no migrations are attempted. (add option to allow partials)
	 * For each migration found in the package definition file:
	 * - Create a classloader, load all artifacts needed for that migration from the package jar.
	 * - Instantiate the migration-handler, run it with the supplied environment.
	 * - Update the migration history to reflect that this migration has been applied.
	 * - Dereference the classloader so it and all its classes are gc'd.
	 */
	
	private final Log log;
	private final JAXBContext context;
	private final JarFile packageJar;
	private final MigrationContainer container;
	private final List<RunListener> listeners;
	private final File workDir;
	
	public Runner(File packageJar) throws Exception {
		
		log = LogFactory.getLog(this.getClass());
		
		if (packageJar == null) {
			throw new NullPointerException();
		}
		
		this.packageJar = new JarFile(packageJar);
		
		context = JAXBContext.newInstance(
			MigrationHistory.class, 
			MigrationContainer.class 
		);
		
		container = extractContainer(packageJar);
		
		listeners = new ArrayList<RunListener>();
		
		workDir = createTempDir();
	}
	
	public void addListener(RunListener l) {
		listeners.add(l);
	}
	
	public void removeListener(RunListener l) {
		listeners.remove(l);
	}
	
	public MigrationHistory readHistory(File file) throws Exception {
		Unmarshaller u = context.createUnmarshaller();
		return (MigrationHistory)u.unmarshal(file);
	}
	
	public void run(String migrationPathName, MigrationHistory history, byte[] env) throws Exception {
		
		MigrationPath path = findPath(migrationPathName);
		if (path == null) {
			throw new RuntimeException("Cannot find a migration path by the name of '" + migrationPathName + "'");
		}
		
		Set<MigrationDef> executed = new HashSet<MigrationDef>();
		for (Migration migration : history.migrations()) {
			executed.add(migration.def());
		}
		
		/*
		 * NOTE: How we determine what migrations to perform could be a lot more
		 * sophisticated. We aren't checking the history to make sure that
		 * migrations are only executed in sequence. This is how schematrax
		 * works, but we might want to improve on it.
		 */
		
		List<MigrationPackage> unexecuted = new ArrayList<MigrationPackage>();
		for (MigrationPackage pkg : path.packages()) {
			
			if (!executed.contains(pkg.def())) {
				unexecuted.add(pkg);
			}
		}
		
		if (unexecuted.isEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug("No migrations remain to be executed, doing nothing.");
			}
			return;
		}
		
		final byte[] buffer = new byte[1024 * 10]; //10k buffer
		
		for (MigrationPackage pkg : unexecuted) {
			
			if (pkg.resources() == null) {
				throw new RuntimeException("Cannot perform migration, migration resource not available in migration jar: " + pkg.def());
			}
			
			if (log.isDebugEnabled()) {
				log.debug("Executing migration: " + pkg.def());
			}

			Migration migration = new Migration(new Instant(), pkg.def());
			MigrationResources res = pkg.resources();

			List<URL> urls = new ArrayList<URL>();
			for (String req : res.classpath()) {

				String[] pathSegments = req.split("\\/");

				File copyTarget = workDir;
				for (String s : pathSegments) {
					copyTarget = new File(copyTarget, s);
				}

				if (!copyTarget.getParentFile().exists() && !copyTarget.getParentFile().mkdirs()) {
					throw new RuntimeException("Cannot create directory: " + copyTarget.getParentFile());
				}

				if (!copyTarget.exists()) {
					
					if (log.isDebugEnabled()) {
						log.debug("Copying classpath resource " + req + " -> " + copyTarget);
					}

					JarEntry entry = packageJar.getJarEntry(req);

					if (entry == null) {
						throw new RuntimeException("Expected package jar entry not found: " + req);
					}

					InputStream in = packageJar.getInputStream(entry);
					OutputStream out = new FileOutputStream(copyTarget);
					for(int numRead = in.read(buffer); numRead!=-1; numRead = in.read(buffer)){
						out.write(buffer, 0, numRead);
					}
					in.close();
					out.close();
				}

				urls.add(copyTarget.toURL());
			}
			
			ClassLoader cl;
			{
				URL[] cp = urls.toArray(new URL[0]);
				cl = new URLClassLoader(cp, null);
			}

			Class clazz = cl.loadClass("com.moss.nomad.api.v1.ClassLoaderBridge");
			
			Method method = clazz.getMethod("execute", String.class, byte[].class);
			
			ClassLoader currentCl = Thread.currentThread().getContextClassLoader();
			try {
				firePreMigration(migration);
				
				/*
				 * NOTE, the reason we're setting the context class loader here
				 * is for java 5 compatibility. JAXBContext seems to load its
				 * classes from the current thread context class loader. In
				 * java 5 this causes problems, in java 6 it doesn't because
				 * the JAXB stuff is in the boot classpath. Ah well.
				 */
				
				Thread.currentThread().setContextClassLoader(cl);
				
				String stacktrace = (String)method.invoke(null, res.className(), env);
				
				Thread.currentThread().setContextClassLoader(currentCl);
				
				if (stacktrace != null) {
					throw new MigrationFailureException(stacktrace);
				}
				
				firePostMigration(migration);
			}
			catch (Exception ex) {
				Thread.currentThread().setContextClassLoader(currentCl);
				
				log.error("Failed to complete migration for migration-def " + pkg.def(), ex);
				fireMigrationFailure(migration, ex);
				throw ex;
			}
		}
	}

	public void close() throws Exception {
		packageJar.close();
		deleteDir(workDir);
	}
	
	private MigrationContainer extractContainer(File f) throws Exception {
		
		ZipFile file = new ZipFile(f);
		ZipEntry entry = new ZipEntry("META-INF/container.xml");
		InputStream in = file.getInputStream(entry);
		Unmarshaller u = context.createUnmarshaller();
		MigrationContainer container = (MigrationContainer)u.unmarshal(in);
		in.close();
		file.close();
		
		return container;
	}
	
	private MigrationPath findPath(String pathName) {
		
		for (MigrationPath p : container.paths()) {
			if (p.name().equals(pathName)) {
				return p;
			}
		}
		
		throw new RuntimeException("Cannot find a migration path by the name of '" + pathName + "'");
	}
	
	private void firePreMigration(Migration migration) {
		for (RunListener l : listeners) {
			l.preMigration(migration);
		}
	}
	
	private void firePostMigration(Migration migration) {
		for (RunListener l : listeners) {
			l.postMigration(migration);
		}
	}
	
	private void fireMigrationFailure(Migration migration, Exception ex) {
		for (RunListener l : listeners) {
			l.migrationFailure(migration, ex);
		}
	}
	
	private static File createTempDir() throws Exception {
		File tmp = File.createTempFile("nomad-runner", "package-jar");
		tmp.delete();
		tmp.mkdirs();
		return tmp;
	}
	
	private static void deleteDir(File file) throws Exception {

		if(file.isDirectory()){
			File[] children = file.listFiles();
			for (File child : children) {
				deleteDir(child);
			}
		}
		if(!file.delete()){
			throw new Exception("Could not delete " + file.getAbsolutePath());
		}
	}
}
