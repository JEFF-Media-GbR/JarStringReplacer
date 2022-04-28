package com.jeff_media.jarstringreplacer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JarStringReplacer {

    public static void main(String... args) throws IOException {

        if(args.length < 3 || args.length % 2 != 0) {
            System.err.println("Invalid number of parameters. Usage: <input.jar> <output.jar> <placeholder1> <replacement1> ...");
            return;
        }

        File inputJar = new File(args[0]);
        File outputJar = new File(args[1]);
        Map<String,String> replacements = new HashMap<>();
        for(int i = 2; i < args.length; i+=2) {
            replacements.put(args[i],args[i+1]);
        }
        new JarStringReplacer(inputJar, outputJar, replacements);
    }

    public JarStringReplacer(File inputJar, File outputJar, Map<String, String> replacements) throws IOException {
        Map<String, ClassNode> nodes = loadClasses(inputJar);
        for(ClassNode cn : nodes.values()) {
            for(MethodNode mn : cn.methods) {
                for(AbstractInsnNode ain : mn.instructions.toArray()) {

                    // LDC = Loading a constant value
                    if(ain.getOpcode() == Opcodes.LDC) {
                        LdcInsnNode ldc = (LdcInsnNode) ain;
                        if(ldc.cst instanceof String) {
                            for(Map.Entry<String,String> entry : replacements.entrySet()) {
                                ldc.cst = ldc.cst.toString().replace(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
            }
        }
        Map<String, byte[]> out = process(nodes, new HashMap<String, String>(), new URLClassLoader(new URL[] {inputJar.toURI().toURL()},ClassLoader.getSystemClassLoader()));
        out.putAll(loadNonClasses(inputJar));
        saveAsJar(out, outputJar);
    }

    Map<String, ClassNode> loadClasses(File jarFile) throws IOException {
        Map<String, ClassNode> classes = new HashMap<String, ClassNode>();
        JarFile jar = new JarFile(jarFile);
        Stream<JarEntry> str = jar.stream();
        str.forEach(z -> readJar(jar, z, classes));
        jar.close();
        return classes;
    }

    Map<String, ClassNode> readJar(JarFile jar, JarEntry entry, Map<String, ClassNode> classes) {
        String name = entry.getName();
        try (InputStream jis = jar.getInputStream(entry)){
            if (name.endsWith(".class")) {
                byte[] bytes = jis.readAllBytes();/*IOUtils.toByteArray(jis);*/
                String cafebabe = String.format("%02X%02X%02X%02X", bytes[0], bytes[1], bytes[2], bytes[3]);
                if (!cafebabe.toLowerCase().equals("cafebabe")) {
                    // This class doesn't have a valid magic
                    return classes;
                }
                try {
                    ClassNode cn = getNode(bytes);
                    classes.put(cn.name, cn);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return classes;
    }

    ClassNode getNode(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        try {
            cr.accept(cn, ClassReader.EXPAND_FRAMES);
        } catch (Exception e) {
            e.printStackTrace();
        }
        cr = null;
        return cn;
    }

    static Map<String, byte[]> process(Map<String, ClassNode> nodes, Map<String, String> mappings, ClassLoader childLoader) {
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        Remapper mapper = new SimpleRemapper(mappings);
        for (ClassNode cn : nodes.values()) {
            //try {
                System.out.println(cn.name);
                ClassWriter cw = /*new ClassWriter(ClassWriter.COMPUTE_FRAMES); */new SafeClassWriter(null, null, ClassWriter.COMPUTE_FRAMES, childLoader);
                ClassVisitor remapper = new ClassRemapper(cw, mapper);
                cn.accept(remapper);
                out.put(mappings.containsKey(cn.name) ? mappings.get(cn.name) : cn.name, cw.toByteArray());
            //} catch (Exception ignored) {
            //    System.out.println("Problem with " + cn.name);
            //}
        }
        return out;
    }

    static Map<String, byte[]> loadNonClasses(File jarFile) throws IOException {
        Map<String, byte[]> entries = new HashMap<String, byte[]>();
        ZipInputStream jis = new ZipInputStream(new FileInputStream(jarFile));
        ZipEntry entry;
        // Iterate all entries
        while ((entry = jis.getNextEntry()) != null) {
            try {
                String name = entry.getName();
                if (!name.endsWith(".class") && !entry.isDirectory()) {
                    // Apache Commons - byte[] toByteArray(InputStream input)
                    //
                    // Add each entry to the map <Entry name , Entry bytes>
                    byte[] bytes = jis.readAllBytes()/*IOUtils.toByteArray(jis)*/;
                    entries.put(name, bytes);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                jis.closeEntry();
            }
        }
        jis.close();
        return entries;
    }

    static void saveAsJar(Map<String, byte[]> outBytes, File fileName) {
        try {
            // Create jar output stream
            JarOutputStream out = new JarOutputStream(new FileOutputStream(fileName));
            // For each entry in the map, save the bytes
            for (String entry : outBytes.keySet()) {
                // Appent class names to class entries
                String ext = entry.contains(".") ? "" : ".class";
                out.putNextEntry(new ZipEntry(entry + ext));
                out.write(outBytes.get(entry));
                out.closeEntry();
            }
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
