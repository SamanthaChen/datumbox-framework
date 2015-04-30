/**
 * Copyright (C) 2013-2015 Vasilis Vryniotis <bbriniotis@datumbox.com>
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
package com.datumbox.configuration;

import com.datumbox.common.persistentstorage.inmemory.InMemoryConfiguration;
import com.datumbox.common.persistentstorage.mapdb.MapDBConfiguration;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConfiguration;

/**
 * Configuration constants for the Tests.
 * 
 * @author Vasilis Vryniotis <bbriniotis@datumbox.com>
 */
public class TestConfiguration {
    //The accuracy of assert on different levels
    public static final double DOUBLE_ACCURACY_HIGH = 0.000001;
    public static final double DOUBLE_ACCURACY_MEDIUM = 0.01;
    public static final double DOUBLE_ACCURACY_LOW = 0.5;
    
    //The initial seed of the RandomGenerator
    public static final long RANDOM_SEED = 42L;
    
    //The class of the DatabaseConfiguration
    public static final Class<? extends DatabaseConfiguration> PERMANENT_STORAGE = InMemoryConfiguration.class; 
    //public static final Class<? extends DatabaseConfiguration> PERMANENT_STORAGE = MapDBConfiguration.class; 
    
}
