/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hpe.simulap.functions;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.functions.AbstractFunction;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.JMeterVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hex2ASCIIFunction extends AbstractFunction {
    private static final Logger LOGGER = LoggerFactory.getLogger(Hex2ASCIIFunction.class);
    private static final String KEY = "__Hex2ASCII"; // $NON-NLS-1$

    private static final List<String> DESC = new LinkedList<String>();

    // Only modified in class init
    private static final Map<String, String> ALIASES = new HashMap<String, String>();

    static {
        DESC.add("Hex string we want to translate to ASCII one");
        DESC.add("reference of variable which contains the result");
    }

    // Ensure that these are set, even if no paramters are provided
    private Object[] values;
    private String input = "";
    private String variable = "";

    public Hex2ASCIIFunction(){
        super();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized String execute(SampleResult previousResult, Sampler currentSampler) {
        input = ((CompoundVariable) values[0]).execute();
        variable = ((CompoundVariable)values[1]).execute().trim();
        LOGGER.info("{}({}, {})", new Object[] {KEY, input, variable});
        
        int l = input.length();
        StringBuilder sb = new StringBuilder(l / 2);
        for (int i = 0; i < l; i+=2) {
            String hex = "" + input.charAt(i) + input.charAt(i+1);
            int ival = Integer.parseInt(hex, 16);
            sb.append((char) ival);
        }
        
        String outputString = sb.toString();

        if (variable.length() > 0) {
            JMeterVariables vars = getVariables();
            if (vars != null){// vars will be null on TestPlan
                vars.put(variable, outputString);
            }
        }
        return outputString;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void setParameters(Collection<CompoundVariable> parameters) throws InvalidVariableException {
        checkParameterCount(parameters, 2, 2);

        values = parameters.toArray();
    }

    /** {@inheritDoc} */
    @Override
    public String getReferenceKey() {
        return KEY;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getArgumentDesc() {
        return DESC;
    }
}