package jdk.tools.jlink.internal;

import java.util.ArrayList;
import java.util.List;

public class TransformationEntry {
    private String className;
    private String methodName;
    private int bci;
    private List<String> parameters;
    private String moduleName;
    public TransformationEntry(String className, String methodName, int bci, String moduleName) {
        this.className = className;
        this.methodName = methodName;
        this.bci = bci;
        this.moduleName = moduleName;
        parameters = new ArrayList<>();
    }

    public void addParameter(String param) {
        parameters.add(param);
    }
    @Override
    public String toString() {
        String params = "";
        for (int i = 0; i < parameters.size(); i++) {
            if (i == 0) params = params.concat(parameters.get(i));
            else params = params.concat(", " + parameters.get(i));
        }
        return String.format("Class %s#%s\n    Bytecode Index: %d.\n    Parameters: %s \n", className, methodName, bci, params);
    }
}
