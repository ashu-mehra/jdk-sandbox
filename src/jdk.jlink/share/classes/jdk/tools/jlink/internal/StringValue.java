package jdk.tools.jlink.internal;

import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;

public class StringValue extends BasicValue {

    private String contents;
    private LdcInsnNode node;
    public StringValue(String contents, LdcInsnNode node) {
        super(Type.getObjectType("java/lang/String"));
        this.contents = contents;
        this.node = node;
    }

    public StringValue(StringValue v) {
        super(Type.getObjectType("java/lang/String"));
        this.contents = new String(v.getContents());
    }

    public String getContents() {
        return contents;
    }

    public LdcInsnNode getLdcNode() { return node; }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (o instanceof StringValue s) {
            String ocontents = s.contents;
            return (ocontents == contents) || (ocontents != null && contents != null
                    && contents.equals(ocontents));
        }
        return false;
    }
}
