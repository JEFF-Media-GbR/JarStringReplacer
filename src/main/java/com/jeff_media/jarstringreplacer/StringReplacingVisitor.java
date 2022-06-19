package com.jeff_media.jarstringreplacer;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

public class StringReplacingVisitor extends ClassVisitor {

    private final Map<String, String> replacements;
    private String className;

    protected StringReplacingVisitor(int api, ClassVisitor classVisitor, Map<String, String> replacements) {
        super(api, classVisitor);
        this.replacements = replacements;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    private static String placeholder(String string, String placeholder) {
        if(string.startsWith("%") && string.endsWith("|orig%")) {
            String replacement = string.substring(1,string.length()-"|orig%".length());
            if(ThreadLocalRandom.current().nextInt(0,100) < 50) return replacement;
            return placeholder;
        }
        if(string.equals("%int%")) return String.valueOf(ThreadLocalRandom.current().nextInt());
        return string;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        return new MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof String oldCst) {
                    for (Map.Entry<String, String> entry : replacements.entrySet()) {
                        String replacement = placeholder(entry.getValue(), entry.getKey());
                        String newValue = oldCst.replace(entry.getKey(), replacement);
                        if (oldCst.contains(entry.getKey())) {
                            System.out.println("Replaced " + entry.getKey() + " in " + className + "." + name + descriptor + " with " + replacement);
                        }
                        oldCst = newValue;
                    }
                    value = oldCst;
                }
                super.visitLdcInsn(value);
            }
        };
    }
}
