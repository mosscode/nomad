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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.moss.nomad.api.v1.MigrationHandler;
import com.moss.nomad.core.def.MigrationDef;
import com.moss.nomad.core.def.MigrationDefs;

public class Packager {

	/*
	 * The packager is given a migration-defs file, along with the number of most recent migrations to include.
	 * It selects which migration defs to include, and for each one it
	 * 
	 * - finds the migration-def artifact in the local maven repository using the migration-def info.
	 * - loads the jar's pom, and determines the full list of the jar's dependencies.
	 * 
	 * - inspects the jar to determine the list of classes in the jar
	 * - loads the jar in a separate classloader to determine which class to use as the update class
	 * 
	 * 
	 * With the above information the packager has enough information to build the package. It copies
	 * the following info into the jar:
	 * - the migration packages file, this goes in the root of the jar. it contains all of the above information
	 * - all the migration-def artifacts and their dependencies: groupId/artifactId/versionId/artifact-qualifier-version.type
	 * 
	 * NOTE: The way the packager resolves/obtains information about a project/deps 
	 * should probably be pluggable since its main use case will be from within an already
	 * running maven instance as a plugin. So maven embedder when standalone and maven 
	 * plugin otherwise.
	 */
	
	private final Log log;
	
	private final JAXBContext context;
	private final PackagerResolver resolver;
	private final int includeMostRecent;
	
	private final MigrationContainer container;
	private final Map<String, ResolvedDependencyInfo> dependencies;
	
	public Packager(PackagerResolver resolver, int includeMostRecent) throws Exception {
		
		log = LogFactory.getLog(this.getClass());
		
		context = JAXBContext.newInstance(
			MigrationDefs.class, 
			MigrationContainer.class 
		);
		
		if (resolver == null) {
			throw new NullPointerException();
		}
		
		this.resolver = resolver;
		this.includeMostRecent = includeMostRecent;
		
		this.container = new MigrationContainer();
		this.dependencies = new HashMap<String, ResolvedDependencyInfo>();
	}
	
	public MigrationDefs readDefs(File file) throws Exception {
		Unmarshaller u = context.createUnmarshaller();
		return (MigrationDefs)u.unmarshal(file);
	}
	
	public void add(String name, List<MigrationDef> defs) throws Exception {
		
		if (log.isDebugEnabled()) {
			log.debug("Adding migration package " + name);
		}
		
		if (migrationPathExists(name)) {
			throw new RuntimeException("Migration path name must be unique: " + name);
		}
		
		MigrationPath path = new MigrationPath(name);
		
		int remaining = defs.size();
		for (MigrationDef def : defs) {
			
			MigrationResources res;
			if (includeMostRecent < 0 || remaining <= includeMostRecent) {
				res = createMigrationResources(def);
			}
			else {
				res = null;
			}

			MigrationPackage pkg = new MigrationPackage(def, res);
			path.add(pkg);
			
			remaining--;
		}
		
		container.add(path);
	}
	
	public void write(OutputStream o) throws Exception {
		
		JarOutputStream out = new JarOutputStream(o);
		
		{
			ByteArrayOutputStream bao = new ByteArrayOutputStream();
			Marshaller m = context.createMarshaller();
			m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
			m.marshal(container, bao);
			
			byte[] containerIndex = bao.toByteArray();
			
			JarEntry entry = new JarEntry("META-INF/container.xml");
			out.putNextEntry(entry);
			out.write(containerIndex);
		}
		
		final byte[] buffer = new byte[1024 * 10]; //10k buffer
		
		for (String path : dependencies.keySet()) {
			ResolvedDependencyInfo info = dependencies.get(path);
			
			JarEntry entry = new JarEntry(path);
			out.putNextEntry(entry);
			
			InputStream in = new FileInputStream(info.file());
			for(int numRead = in.read(buffer); numRead!=-1; numRead = in.read(buffer)){
				out.write(buffer, 0, numRead);
			}
			in.close();
		}
		
		out.close();
	}
	
	public void clear() {
		container.paths().clear();
		dependencies.clear();
	}
	
	private boolean migrationPathExists(String pathName) {
		
		for (MigrationPath p : container.paths()) {
			if (p.name().equals(pathName)) {
				return true;
			}
		}
		
		return false;
	}
	
	private MigrationResources createMigrationResources(MigrationDef def) throws Exception {
		
		ResolvedMigrationInfo info = resolver.resolve(def);
		
		/*
		 * Determine the classpath for this migration package.
		 */
		
		List<ResolvedDependencyInfo> dependencyArtifacts = new ArrayList<ResolvedDependencyInfo>();
		dependencyArtifacts.add(new ResolvedDependencyInfo(
			def.groupId(),
			def.artifactId(),
			def.version(),
			def.type(),
			def.classifier(),
			info.migrationArtifact()
		));
		dependencyArtifacts.addAll(info.dependencyArtifacts());
		
		List<String> handlerClassPath = new ArrayList<String>();
		for (ResolvedDependencyInfo dep : dependencyArtifacts) {
			
			StringBuilder sb = new StringBuilder();
			sb.append(dep.groupId());
			sb.append("/").append(dep.artifactId());
			sb.append("/").append(dep.version());
			sb.append("/").append(dep.artifactId());
			
			if (dep.classifier() != null) {
				sb.append("-").append(dep.classifier());
			}
			
			sb.append("-").append(dep.version());
			sb.append(".").append(dep.type());
			
			String pathName = sb.toString();
			
			handlerClassPath.add(pathName);
			
			if (!dependencies.containsKey(pathName)) {
				dependencies.put(pathName, dep);
			}
		}

		/*
		 * Determine which class is the migration handler. There can only be
		 * one, any other case will cause an exception to be thrown.
		 */
		
		URL[] cp;
		{
			List<URL> urls = new ArrayList<URL>();
			
			urls.add(info.migrationArtifact().toURL());
			
			for (ResolvedDependencyInfo d : dependencyArtifacts) {
				urls.add(d.file().toURL());
			}
			
			cp = urls.toArray(new URL[0]);
		}
		
		ClassLoader cl = new URLClassLoader(cp, null);
		
		List<String> handlerClassNames = new ArrayList<String>();
		for (String jarPath : listJarPaths(info.migrationArtifact())) {
			
			if (!jarPath.endsWith(".class")) {
				continue;
			}
			
			String className = jarPath.replaceAll("\\/", ".").substring(0, jarPath.length() - 6);
			Class clazz = cl.loadClass(className);
			
			for (Class iface : clazz.getInterfaces()) {
				if (iface.getName().equals(MigrationHandler.class.getName())) {
					handlerClassNames.add(className);
					break;
				}
			}
		}
		
		String handlerClassName;
		if (handlerClassNames.isEmpty()) {
			
			StringBuilder sb = new StringBuilder();
			sb.append("Could not find an implementation of ");
			sb.append(MigrationHandler.class.getName());
			sb.append(" in migration def jar ");
			sb.append(def.toString());
			sb.append(": there must be exactly one.\n");
			
			throw new RuntimeException(sb.toString());
		}
		else if (handlerClassNames.size() > 1) {
			
			StringBuilder sb = new StringBuilder();
			sb.append("Found more than one implementation of ");
			sb.append(MigrationHandler.class.getName());
			sb.append(" in migration def jar ");
			sb.append(def.toString());
			sb.append(": there must be exactly one.\n");
			
			for (String n : handlerClassNames) {
				sb.append("    --> ").append(n).append("\n");
			}
			
			throw new RuntimeException(sb.toString());
		}
		else {
			handlerClassName = handlerClassNames.get(0);
		}
		
		return new MigrationResources(handlerClassName, handlerClassPath);
	}
	
	private static String[] listJarPaths(File file) throws Exception {
		
		JarFile jar = new JarFile(file);
		Enumeration<JarEntry> e = jar.entries();
		List<String> paths = new ArrayList<String>();
		
		while (e.hasMoreElements()) {
			JarEntry entry = e.nextElement();
			paths.add(entry.getName());
		}
		
		return paths.toArray(new String[0]);
	}
}
