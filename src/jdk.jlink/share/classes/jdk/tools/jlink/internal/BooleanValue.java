package jdk.tools.jlink.internal;

import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;

public class BooleanValue extends BasicValue {

    private boolean value;

    public BooleanValue(boolean value) {
        super(Type.getObjectType("boolean"));
        this.value = value;
    }

    public boolean getValue() {
        return this.value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null) return false;
        if (other instanceof BooleanValue b) {
            Boolean ovalue = b.value;
            return ovalue == value;
        }
        return false;
    }
}
