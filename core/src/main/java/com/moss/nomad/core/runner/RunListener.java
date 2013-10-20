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

import com.moss.nomad.core.history.Migration;

/**
 * Its the run listener's job to update whatever the source of the migration
 * history happens to be. I.e., when preMigration() is called, it might be
 * a good idea in a transacted environment to start a transaction and update
 * the history. When postMigration() is called, you could then commit the tx.
 * When migrationFailure() is called, you could roll back the transaction.
 */
public interface RunListener {

	/**
	 * Called once before nomad attempts a migration.
	 */
	void preMigration(Migration migration);
	
	/**
	 * Called once after nomad has successfully performed a migration.
	 */
	void postMigration(Migration migration);

	/**
	 * Called once if nomad has attempted and failed to perform a migration. 
	 */
	void migrationFailure(Migration migration, Exception ex);
}
