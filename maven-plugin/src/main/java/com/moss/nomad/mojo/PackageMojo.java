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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

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
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.MavenProjectHelper;

import com.moss.nomad.api.v1.Classpath;
import com.moss.nomad.api.v1.ClasspathEntry;
import com.moss.nomad.core.def.MigrationDef;
import com.moss.nomad.core.def.MigrationDefs;
import com.moss.nomad.core.packager.Packager;
import com.moss.nomad.core.packager.PackagerResolver;
import com.moss.nomad.core.packager.ResolvedDependencyInfo;
import com.moss.nomad.core.packager.ResolvedMigrationInfo;

/**
 * @goal package
 */
public class PackageMojo extends AbstractMojo {
	
	/*
	 * Injected maven-specific parameters.
	 */

	/** @component */
	private ArtifactFactory artifactFactory;
	
	/** @component */
	private ArtifactResolver resolver;
	
	/** @component */
	private ArtifactMetadataSource metadata;
	
	/** @component */
	private MavenProjectBuilder projectBuilder;
	
	/** @component */
	private MavenProjectHelper projectHelper;
	
	/** @parameter expression="${project}" */
	private MavenProject project;

	/**@parameter expression="${localRepository}" */
	private ArtifactRepository local;
	
	/** @parameter expression="${project.remoteArtifactRepositories}" */
	private List<ArtifactRepository> remote;
	
    /*
     * Injected nomad-specific parameters
     */
    
    /** @parameter */
    private List<MigrationPath> migrationPaths;
    
    /** @parameter default-value="-1" */
    private Integer includeMostRecent;
    
    /** @parameter */
    private String filename;

	public void execute() throws MojoExecutionException, MojoFailureException {
		
		try {
			Packager packager = new Packager(new MojoResolver(), includeMostRecent);

			for (MigrationPath path : migrationPaths) {
				MigrationDefs defs = packager.readDefs(path.file);
				packager.add(path.name, defs.defs());
			}
			
			if (filename == null) {
				filename = project.getArtifactId() + "-nomad-" + project.getVersion() + ".jar";
			}
			
			File file = new File(project.getBuild().getDirectory(), filename);
			
			if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
				throw new RuntimeException("Could not create dir: " + file.getParentFile());
			}
			
			if (getLog().isInfoEnabled()) {
				getLog().info("Writing migration jar: " + file);
			}
			
			FileOutputStream out = new FileOutputStream(file);
			packager.write(out);
			out.close();
			
			projectHelper.attachArtifact(project, file, "nomad");
		}
		catch (Exception ex) {
			throw new MojoExecutionException("oops", ex);
		}
	}
	
	private class MojoResolver implements PackagerResolver {
		public ResolvedMigrationInfo resolve(MigrationDef def) throws Exception {
			
			Artifact migrationArtifact = artifactFactory.createArtifact(
				def.groupId(), 
				def.artifactId(), 
				def.version(),
				null, 
				def.type()
			);
			
			resolver.resolve(migrationArtifact, remote, local);
			
			Set<Artifact> dependencyArtifacts = new HashSet<Artifact>();
			dependencyArtifacts.add(migrationArtifact);
			
			Classpath classpath;
			try {
				JarFile jar = new JarFile(migrationArtifact.getFile());
				JarEntry entry = jar.getJarEntry("META-INF/classpath.xml");
				
				if (entry == null) {
					classpath = null;
				}
				else {
					InputStream in = jar.getInputStream(entry);
					JAXBContext ctx = JAXBContext.newInstance(Classpath.class);
					Unmarshaller u = ctx.createUnmarshaller();
					classpath = (Classpath)u.unmarshal(in);
					in.close();
				}
				
				jar.close();
			}
			catch (Exception ex) {
				getLog().warn("Found META-INF/classpath.xml in migration artifact " + migrationArtifact + " but could not read it, building classpath manually", ex);
				classpath = null;
			}
			
			if (classpath != null) {
				
				if (getLog().isInfoEnabled()) {
					getLog().info("META-INF/classpath.xml found, using it");
				}
				
				for (ClasspathEntry e : classpath.entries()) {
					
					Artifact entryArtifact = artifactFactory.createArtifact(
						e.groupId(), 
						e.artifactId(), 
						e.version(),
						null, 
						e.type()
					);
						
					resolver.resolve(entryArtifact, remote, local);
					
					dependencyArtifacts.add(entryArtifact);
				}
			}
			else {
				
				if (getLog().isInfoEnabled()) {
					getLog().info("META-INF/classpath.xml not found, determining migration dependencies dynamically");
				}
				
				Artifact pomArtifact = artifactFactory.createArtifact(
					def.groupId(), 
					def.artifactId(), 
					def.version(), 
					null, 
					"pom"
				);

				MavenProject pomProject = projectBuilder.buildFromRepository(pomArtifact, remote, local);

				Set a = pomProject.createArtifacts(artifactFactory, null, null);

				ArtifactResolutionResult res = resolver.resolveTransitively(
					a, 
					pomArtifact, 
					remote, 
					local, 
					metadata
				);
				
				if (!res.getArtifacts().isEmpty()) {
					dependencyArtifacts.addAll((Set<Artifact>)res.getArtifacts());
				}
			}
			
			List<ResolvedDependencyInfo> dependencyInfo = new ArrayList<ResolvedDependencyInfo>();
			for (Artifact a : dependencyArtifacts) {

				ResolvedDependencyInfo info = new ResolvedDependencyInfo(
					a.getGroupId(),
					a.getArtifactId(),
					a.getVersion(),
					a.getType(),
					a.getClassifier(),
					a.getFile()
				);

				dependencyInfo.add(info);
			}

			return new ResolvedMigrationInfo(migrationArtifact.getFile(), dependencyInfo);
		}
	}
}
