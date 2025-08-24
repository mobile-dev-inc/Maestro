/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.orchestra.yaml

data class YamlInputRandomText(
    val length: Int?,
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlInputRandomNumber(
    val length: Int?,
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlInputRandomEmail(
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlInputRandomPersonName(
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlInputRandomCityName(
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlInputRandomCountryName(
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlInputRandomColorName(
    val label: String? = null,
    val optional: Boolean = false,
)