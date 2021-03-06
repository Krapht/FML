/*
 * Forge Mod Loader
 * Copyright (c) 2012-2013 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     cpw - implementation
 */

package cpw.mods.fml.common.asm;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.ObjectInputStream.GetField;
import java.io.StringReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.CodeSource;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.zip.ZipEntry;

import javax.swing.JOptionPane;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

import cpw.mods.fml.common.CertificateHelper;
import cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import cpw.mods.fml.common.patcher.ClassPatchManager;
import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.IFMLCallHook;
import cpw.mods.fml.relauncher.Side;

public class FMLSanityChecker implements IFMLCallHook
{
    private static final String FMLFINGERPRINT =   "51:0A:FB:4C:AF:A4:A0:F2:F5:CF:C5:0E:B4:CC:3C:30:24:4A:E3:8E".toLowerCase().replace(":", "");
    private static final String FORGEFINGERPRINT = "DE:4C:F8:A3:F3:BC:15:63:58:10:04:4C:39:24:0B:F9:68:04:EA:7D".toLowerCase().replace(":", "");
    private static final String MCFINGERPRINT =    "CD:99:95:96:56:F7:53:DC:28:D8:63:B4:67:69:F7:F8:FB:AE:FC:FC".toLowerCase().replace(":", "");
    static class MLDetectorClassVisitor extends ClassVisitor
    {
        private boolean foundMarker = false;
        private MLDetectorClassVisitor()
        {
            super(Opcodes.ASM4);
        }

        @Override
        public FieldVisitor visitField(int arg0, String arg1, String arg2, String arg3, Object arg4)
        {
            if ("fmlMarker".equals(arg1))
            {
                foundMarker = true;
            }
            return null;
        }
    }

    private LaunchClassLoader cl;

    @Override
    public Void call() throws Exception
    {
        CodeSource codeSource = getClass().getProtectionDomain().getCodeSource();
        boolean goodFML = false;
        boolean fmlIsJar = false;
        if (codeSource.getLocation().getProtocol().equals("jar"))
        {
            fmlIsJar = true;
            Certificate[] certificates = codeSource.getCertificates();
            if (certificates!=null)
            {

                for (Certificate cert : certificates)
                {
                    String fingerprint = CertificateHelper.getFingerprint(cert);
                    if (fingerprint.equals(FMLFINGERPRINT))
                    {
                        FMLRelaunchLog.info("Found valid fingerprint for FML. Certificate fingerprint %s", fingerprint);
                        goodFML = true;
                    }
                    else if (fingerprint.equals(FORGEFINGERPRINT))
                    {
                        FMLRelaunchLog.info("Found valid fingerprint for Minecraft Forge. Certificate fingerprint %s", fingerprint);
                        goodFML = true;
                    }
                    else
                    {
                        FMLRelaunchLog.severe("Found invalid fingerprint for FML: %s", fingerprint);
                    }
                }
            }
        }
        else
        {
            goodFML = true;
        }

        boolean goodMC = FMLLaunchHandler.side() == Side.SERVER; //Server is not signed, so assume it's good.
        try
        {
            Class cbr = Class.forName("net.minecraft.server.MinecraftServer",false, cl);
            codeSource = cbr.getProtectionDomain().getCodeSource();
        }
        catch (Exception e)
        {
            // Probably a development environment
            goodMC = true;
        }
        if (fmlIsJar && !goodMC && codeSource.getLocation().getProtocol().equals("jar"))
        {
            try
            {
                String mcPath = codeSource.getLocation().getPath().substring(5);
                mcPath = mcPath.substring(0, mcPath.lastIndexOf('!'));
                mcPath = URLDecoder.decode(mcPath, Charsets.UTF_8.name());
                JarFile mcJarFile = new JarFile(mcPath,true);
                mcJarFile.getManifest();
                JarEntry serverEntry = mcJarFile.getJarEntry("net/minecraft/server/MinecraftServer.class");
                ByteStreams.toByteArray(mcJarFile.getInputStream(serverEntry));
                Certificate[] certificates = serverEntry.getCertificates();
                if (certificates!=null)
                {

                    for (Certificate cert : certificates)
                    {
                        String fingerprint = CertificateHelper.getFingerprint(cert);
                        if (fingerprint.equals(MCFINGERPRINT))
                        {
                            FMLRelaunchLog.info("Found valid fingerprint for Minecraft. Certificate fingerprint %s", fingerprint);
                            goodMC = true;
                        }
                    }
                }
            }
            catch (Throwable e)
            {
                FMLRelaunchLog.log(Level.SEVERE, e, "A critical error occurred trying to read the minecraft jar file");
            }
        }
        else
        {
            goodMC = true;
        }
        if (!goodMC)
        {
            FMLRelaunchLog.severe("The minecraft jar %s appears to be corrupt! There has been CRITICAL TAMPERING WITH MINECRAFT, it is highly unlikely minecraft will work! STOP NOW, get a clean copy and try again!",codeSource.getLocation().getFile());
            if (!Boolean.parseBoolean(System.getProperty("fml.ignoreInvalidMinecraftCertificates","false")))
            {
                FMLRelaunchLog.severe("For your safety, FML will not launch minecraft. You will need to fetch a clean version of the minecraft jar file");
                System.exit(1);
            }
            else
            {
                FMLRelaunchLog.severe("FML has been ordered to ignore the invalid or missing minecraft certificate. THIS IS A VERY DANGEROUS THING TO DO");

            }
        }
        if (!goodFML)
        {
            FMLRelaunchLog.severe("FML appears to be missing any signature data. This is not a good thing");
        }
        byte[] mlClass = cl.getClassBytes("ModLoader");
        // Only care in obfuscated env
        if (mlClass == null)
        {
            return null;
        }
        MLDetectorClassVisitor mlTester = new MLDetectorClassVisitor();
        ClassReader cr = new ClassReader(mlClass);
        cr.accept(mlTester, ClassReader.SKIP_CODE);
        if (!mlTester.foundMarker)
        {
            JOptionPane.showMessageDialog(null, "<html>CRITICAL ERROR<br/>" +
            		"ModLoader was detected in this environment<br/>" +
                        "ForgeModLoader cannot be installed alongside ModLoader<br/>" +
                        "All mods should work without ModLoader being installed<br/>" +
                        "Because ForgeModLoader is 100% compatible with ModLoader<br/>" +
                        "Re-install Minecraft Forge or Forge ModLoader into a clean<br/>" +
                        "jar and try again.",
                        "ForgeModLoader critical error",
                        JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException("Invalid ModLoader class detected");
        }
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data)
    {
        cl = (LaunchClassLoader) data.get("classLoader");
        File mcDir = (File)data.get("mcLocation");
        FMLDeobfuscatingRemapper.INSTANCE.setup(mcDir, cl, (String) data.get("deobfuscationFileName"));
        File binpatches = new File(mcDir,"binpatch");
        ClassPatchManager.INSTANCE.setup(FMLLaunchHandler.side());
    }

}
