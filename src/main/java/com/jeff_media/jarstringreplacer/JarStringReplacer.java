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
import java.util.concurrent.ThreadLocalRandom;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JarStringReplacer {

    private static final String[] USAGE = {
            "Usage: [options] <input.jar> <output.jar> <placeholder1> <replacement1> ...",
            "",
            "Options: ",
            " -c <classname> - Only replaces strings in classes containing <classname>",
            " -spigot50      - Replace 50% of all SpigotMC placeholders with 1111/2222/3333",
            " -spigot100     - Replace all SpigotMC placeholders with 1111/2222/3333",
            "",
            "Placeholders: ",
            " %int% - Random integer",
            " %<*>|orig% - Uses <*> or the original placeholder name"
    };

    private static int getNonNullLength(String[] args) {
        int i = 0;
        for(String string : args) {
            if(string != null) i++;
        }
        return i;
    }

    private static String[] getNewArgs(String[] args) {
        String[] newArgs = new String[getNonNullLength(args)];
        int newIndex = 0;
        for (String arg : args) {
            if (arg != null) newArgs[newIndex++] = arg;
        }
        return newArgs;
    }

    public static void main(String... args) throws IOException {

        String classMatcher = null;

        Map<String,String> replacements = new HashMap<>();

        for(int i = 0; i < args.length; i++) {
            if(args[i] == null) continue;
            if(args[i].equals("-c")) {
                classMatcher = args[i+1];
                args[i] = null;
                args[i+1] = null;
            } else if(args[i].equals("-spigot50")) {
                replacements.put("%%__USER__%%","%1111|orig%");
                replacements.put("%%__RESOURCE__%%","%2222|orig%");
                replacements.put("%%__NONCE__%%","%3333|orig%");
                args[i] = null;
            } else if(args[i].equals("-spigot100")) {
                replacements.put("%%__USER__%%","1111");
                replacements.put("%%__RESOURCE__%%","2222");
                replacements.put("%%__NONCE__%%","3333");
                args[i] = null;
            } else if(args[i].startsWith("-")) {
                System.err.println("Unknown option: " + args[i]);
                usage();
                return;
            }
        }

        if(getNonNullLength(args) < 1 || getNonNullLength(args) % 2 != 0) {
            System.err.println("Invalid number of parameters.");
            usage();
            return;
        }

        args = getNewArgs(args);

        File inputJar = new File(args[0]);
        File outputJar = new File(args[1]);
        for(int i = 2; i < args.length; i+=2) {
            replacements.put(args[i],args[i+1]);
        }
        new JarStringReplacer(classMatcher, inputJar, outputJar, replacements);
    }

    private static void usage() {
        for(String line : USAGE) {
            System.out.println(line);
        }
    }

    public JarStringReplacer(String classMatcher, File inputJar, File outputJar, Map<String, String> replacements) throws IOException {
        Map<String, ClassNode> nodes = loadClasses(inputJar);
        for(ClassNode cn : nodes.values()) {
            if(classMatcher == null || cn.name.contains(classMatcher)) {
                for (MethodNode mn : cn.methods) {
                    for (AbstractInsnNode ain : mn.instructions.toArray()) {

                        // LDC = Loading a constant value
                        if (ain.getOpcode() == Opcodes.LDC) {
                            LdcInsnNode ldc = (LdcInsnNode) ain;
                            if (ldc.cst instanceof String) {
                                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                                    String oldCst = ldc.cst.toString();
                                    String replacement = placeholder(entry.getValue(),entry.getKey());
                                    ldc.cst = ldc.cst.toString().replace(entry.getKey(), replacement);
                                    String newCst = ldc.cst.toString();
                                    if (oldCst.contains(entry.getKey())) {
                                        System.out.println("Replaced " + entry.getKey() + " in " + cn.name + " at " + mn.name + " with " + replacement);
                                    }
                                }
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

    private static String placeholder(String string, String placeholder) {
        if(string.startsWith("%") && string.endsWith("|orig%")) {
            String replacement = string.substring(1,string.length()-"|orig%".length());
            if(ThreadLocalRandom.current().nextInt(0,100) < 50) return replacement;
            return placeholder;
        }
        if(string.equals("%int%")) return String.valueOf(ThreadLocalRandom.current().nextInt());
        return string;
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
            try {
                //System.out.println(cn.name);
                ClassWriter cw = /*new ClassWriter(ClassWriter.COMPUTE_FRAMES); */new SafeClassWriter(null, null, ClassWriter.COMPUTE_FRAMES, childLoader);
                ClassVisitor remapper = new ClassRemapper(cw, mapper);
                cn.accept(remapper);
                out.put(mappings.containsKey(cn.name) ? mappings.get(cn.name) : cn.name, cw.toByteArray());
            } catch (Exception ignored) {
                System.out.println("Problem with " + cn.name);
            }
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
