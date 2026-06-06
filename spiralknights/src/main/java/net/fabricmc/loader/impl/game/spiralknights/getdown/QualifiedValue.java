/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.game.spiralknights.getdown;

final class QualifiedValue {
	private final String qualifier;
	private final String value;

	private QualifiedValue(String qualifier, String value) {
		this.qualifier = qualifier;
		this.value = value;
	}

	/**
	 * Getdown can declare values that start with OS tags.
	 *
	 * <p>For example, {@code code = [windows] code/etc.jar} only applies on Windows.
	 */
	static QualifiedValue parse(String value) {
		if (!value.startsWith("[")) {
			return new QualifiedValue(null, value);
		}

		int end = value.indexOf(']');

		if (end < 0) {
			return new QualifiedValue(null, value);
		}

		String qualifier = value.substring(1, end).trim();
		String remaining = value.substring(end + 1).trim();
		return new QualifiedValue(qualifier, remaining);
	}

	String getValue() {
		return value;
	}

	boolean appliesToCurrentPlatform() {
		return qualifier == null || qualifier.isEmpty() || appliesToCurrentPlatform(qualifier);
	}

	boolean appliesToCurrentPlatform(String qualifier) {
		return CurrentOs.get().matchesGetdownQualifier(qualifier);
	}
}
