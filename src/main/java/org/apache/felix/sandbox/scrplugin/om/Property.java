/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.sandbox.scrplugin.om;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.felix.sandbox.scrplugin.tags.JavaTag;

/**
 * <code>Property.java</code>...
 *
 */
public class Property extends AbstractObject {

    protected String name;
    protected String value;
    protected String type;
    protected String text;

    /**
     * Default constructor.
     */
    public Property() {
        this(null);
    }

    /**
     * Constructor from java source.
     */
    public Property(JavaTag t) {
        super(t);
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
        this.text = null;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
        this.value = null;
    }

    public void setValues(Map valueMap) {
        this.value = null;
        this.text = "";
        for (Iterator vi = valueMap.entrySet().iterator(); vi.hasNext();) {
            Map.Entry entry = (Map.Entry) vi.next();
            String key = (String) entry.getKey();
            if (key.startsWith("values")) {
                this.text = this.text + entry.getValue() + "\n";
            }
        }
    }

    /**
     * Validate the property.
     * If errors occur a message is added to the issues list,
     * warnings can be added to the warnings list.
     */
    public void validate(List issues, List warnings) {
        // might want to check name and type
    }
}
