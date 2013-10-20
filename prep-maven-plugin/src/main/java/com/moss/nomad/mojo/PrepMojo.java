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
package com.moss.nomad.mojo;

import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import com.moss.nomad.api.v1.Classpath;
import com.moss.nomad.api.v1.ClasspathEntry;
import com.moss.nomad.api.v1.MigrationHandler;

/**
 * @goal prep
 */
public class PrepMojo extends AbstractMojo {
	
	/** @component */
	private ArtifactFactory artifactFactory;
	
	/** @component */
	private ArtifactResolver resolver;
	
	/** @component */
	private ArtifactMetadataSource metadata;
	
	/** @parameter expression="${project}" */
	private MavenProject project;

	/**@parameter expression="${localRepository}" */
	private ArtifactRepository local;
	
	/** @parameter expression="${project.remoteArtifactRepositories}" */
	private List<ArtifactRepository> remote;

	public void execute() throws MojoExecutionException, MojoFailureException {
		try {
			
			/*
			 * Resolve all of this project's dependencies
			 */
			
			Set<Artifact> artifacts;
			{
				Set a = project.createArtifacts(artifactFactory, null, null);

				ArtifactResolutionResult res = resolver.resolveTransitively(
					a, 
					project.getArtifact(), 
					remote, 
					local, 
					metadata
				);
				
				Set b = res.getArtifacts();
				
				if (b.isEmpty()) {
					artifacts = new HashSet<Artifact>();
				}
				else {
					artifacts = (Set<Artifact>)b;
				}
			}
			
			/*
			 * Compile a list of the classes in this project. 
			 */
			
			List<String> classNames;
			{
				String root;
				{
					File sourceDir = new File(project.getBasedir(), "src");
					File mainDir = new File(sourceDir, "main");
					File javaDir = new File(mainDir, "java").getAbsoluteFile();

					if (!javaDir.exists() && !javaDir.mkdirs()) {
						throw new RuntimeException("Can't create dir: " + javaDir);
					}
					
					root = javaDir.getAbsolutePath();
				}
				
				classNames = new ArrayList<String>();
				int rootLength = root.length();
				
				for (File f : files(new File(root))) {
					
					if (!f.isFile() || !f.getName().endsWith(".java")) {
						continue;
					}
					
					String path = f .getAbsolutePath();
					path = path.substring(rootLength + 1, path.length() - 5);
					path = path.replaceAll("\\/", ".");
					
					classNames.add(path);
				}
			}
			
			/*
			 * Inspect the classes to determine which one is the migration handler.
			 * This is done purely for some front-end validation.
			 */
			
			String handlerClassName;
			{
				List<URL> urls = new ArrayList<URL>();
				
				urls.add(new File(project.getBuild().getDirectory(), "classes").toURL());
				
				for (Artifact artifact : artifacts) {
					urls.add(artifact.getFile().toURL());
				}
				
				URL[] cp = urls.toArray(new URL[0]);
				ClassLoader cl = new URLClassLoader(cp, null);
				
				List<String> handlerClassNames = new ArrayList<String>();
				
				for (String className : classNames) {
					
					Class clazz = cl.loadClass(className);
					
					for (Class iface : clazz.getInterfaces()) {
						if (iface.getName().equals(MigrationHandler.class.getName())) {
							handlerClassNames.add(className);
							break;
						}
					}
				}
				
				if (handlerClassNames.isEmpty()) {
					
					StringBuilder sb = new StringBuilder();
					sb.append("Could not find an implementation of ");
					sb.append(MigrationHandler.class.getName());
					sb.append(" in migration def classes: there must be exactly one.\n");
					
					throw new RuntimeException(sb.toString());
				}
				else if (handlerClassNames.size() > 1) {
					
					StringBuilder sb = new StringBuilder();
					sb.append("Found more than one implementation of ");
					sb.append(MigrationHandler.class.getName());
					sb.append(" in migration def classes: there must be exactly one.\n");
					
					for (String n : handlerClassNames) {
						sb.append("    --> ").append(n).append("\n");
					}
					
					throw new RuntimeException(sb.toString());
				}
				else {
					handlerClassName = handlerClassNames.get(0);
				}
			}
			
			/*
			 * Build the classpath file
			 */

			File classpathFile;
			{
				File classesDir = new File(project.getBuild().getDirectory(), "classes");
				File metaDir = new File(classesDir, "META-INF");
				
				if (!metaDir.exists() && !metaDir.mkdirs()) {
					throw new RuntimeException("Can't create dir: " + metaDir);
				}

				classpathFile = new File(metaDir, "classpath.xml");
			}
			
			if (getLog().isInfoEnabled()) {
				getLog().info("Found handler " + handlerClassName);
			}

			Classpath classpathEntries = new Classpath();
			
			artifacts.add(project.getArtifact());

			for (Artifact artifact : artifacts) {

				ClasspathEntry entry = new ClasspathEntry(
					artifact.getGroupId(),
					artifact.getArtifactId(),
					artifact.getVersion(),
					artifact.getType(),
					artifact.getClassifier()
				);
				
				classpathEntries.add(entry);
			}
			
			JAXBContext ctx = JAXBContext.newInstance(Classpath.class);
			Marshaller m = ctx.createMarshaller();
			m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
			m.marshal(classpathEntries, new FileWriter(classpathFile));
		}
		catch (Exception e) {
			throw new MojoExecutionException("Failed to generate classpath.xml", e);
		}
	}
	
	private List<File> files(File root) {
		List<File> f = new ArrayList<File>();
		files(root, f);
		return f;
	}
	
	private void files(File root, List<File> files) {
		
		if (root.isFile()) {
			files.add(root.getAbsoluteFile());
		}
		else {
			for (File f : root.listFiles()) {
				files (f, files);
			}
		}
	}
}
