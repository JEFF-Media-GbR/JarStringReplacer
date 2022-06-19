package com.jeff_media.jarstringreplacer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(inputJar));
                ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(outputJar))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                zipOut.putNextEntry(entry);

                if (!entry.getName().endsWith(".class")) {
                    zipIn.transferTo(zipOut);
                    continue;
                }

                byte[] data = zipIn.readAllBytes();
                // Unfortunately, ints are signed - which means we have to demote the ints to bytes
                // so that the values to match up correctly
                if (data.length > 4 && data[0] == ((byte) 0xCA) && data[1] == ((byte) 0xFE) && data[2] == ((byte) 0xBA) && data[3] == ((byte) 0xBE)) { // All valid class files start with 0xCAFEBABE
                    try {
                        ClassReader reader = new ClassReader(data);
                        ClassWriter writer = new ClassWriter(reader, 0);
                        reader.accept(new StringReplacingVisitor(Opcodes.ASM9, writer, replacements), 0);
                        zipOut.write(writer.toByteArray());
                    } catch (Throwable t) {
                        // Either the class is not an actual class file or contains ASM crashers.
                        // TODO use CAFED00D instead to prevent latter
                        // -> write pristine data
                        zipOut.write(data);
                        t.printStackTrace();
                        System.out.println("Failed to write/read " + entry.getName());
                    }
                } else {
                    zipOut.write(data);
                }
            }
        }
    }
}
