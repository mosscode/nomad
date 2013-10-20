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
package com.moss.nomad.api.v1;

import java.io.PrintWriter;
import java.io.StringWriter;

public class ClassLoaderBridge {
	
	public static String execute(String className, byte[] serialEnv) {
		
		try {
			Class handlerClass = ClassLoaderBridge.class.getClassLoader().loadClass(className);
	
			EnvReader reader;
			{
				EnvAdapter adapter = (EnvAdapter) handlerClass.getAnnotation(EnvAdapter.class);
				
				if (adapter != null && adapter.value() != null) {
					reader = (EnvReader)adapter.value().newInstance();
				}
				else {
					reader = new EnvReader<byte[]>() {
						public byte[] read(byte[] env) throws Exception {
							return env;
						}
					};
				}
			}
			
			Object env = reader.read(serialEnv);
			
			MigrationHandler handler = (MigrationHandler) handlerClass.newInstance();
			handler.execute(env);
			
			return null;
		}
		catch (Exception ex) {
			
			String stackTrace;
			try {
				StringWriter w = new StringWriter();
				PrintWriter pw = new PrintWriter(w);
				ex.printStackTrace(pw);
				stackTrace = w.getBuffer().toString();
			}
			catch (Exception ex2) {
				ex.printStackTrace();
				throw new RuntimeException("Failed to report failure");
			}
			return stackTrace;
		}
	}
	
//	public static String environmentClass(String className) {
//
//		try {
//			Class clazz = Class.forName(className);
//			String found = null;
//
//			for (Type iface : clazz.getGenericInterfaces()) {
//
//				if (iface instanceof ParameterizedType) {
//
//					ParameterizedType pt = (ParameterizedType) iface;
//					Class classType = (Class<?>)pt.getRawType();
//
//					if (classType.getName().equals(MigrationHandler.class.getName())) {
//
//						Type type = pt.getActualTypeArguments()[0];
//						found = getRootClass(type).getName();
//						break;
//					}
//				}
//			}
//
//			return found;
//		}
//		catch (Exception ex) {
//			throw new RuntimeException(ex);
//		}
//	}
//
//	private static Class getRootClass(Type type){
//		if (type instanceof Class) { 
//			return (Class)type;
//		}
//		else if (type instanceof ParameterizedType) {
//			return getRootClass((ParameterizedType)type);
//		}
//		return null;
//	}
}
