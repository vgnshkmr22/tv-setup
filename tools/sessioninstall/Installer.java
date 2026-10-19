// Stages an APK into a PackageInstaller session, run as the shell user with app_process.
//
// Needed on TVs whose firmware patches the `pm install` shell command to refuse installs (TCL:
// "install apk has be disabled from pm by system default"). The session API is the path the TV's own
// app store uses and is not gated. Staging and committing in one process does not work on those TVs:
// the process is killed inside commit(), which aborts the session. So this only stages and prints the
// session id; tools/session-install.sh finishes with `pm install-commit`, which is not gated.
//
// Everything hidden (ServiceManager, IPackageManager, the PackageInstaller constructor) is reached by
// reflection so this compiles against the public android.jar.
import android.content.pm.PackageInstaller;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class Installer {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Installer <apk>");
            System.exit(2);
        }
        // The package parser rejects anything that does not end in .apk.
        File apk = new File(args[0]);
        if (!apk.isFile() || !apk.getName().endsWith(".apk")) {
            System.err.println("not an .apk file: " + apk);
            System.exit(2);
        }

        PackageInstaller installer = packageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        int id = installer.createSession(params);

        PackageInstaller.Session session = installer.openSession(id);
        try {
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                out.close();
                in.close();
            }
        } finally {
            session.close();
        }
        System.out.println("SESSION_ID=" + id);
    }

    private static PackageInstaller packageInstaller() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
        Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object installer = Class.forName("android.content.pm.IPackageManager")
                .getMethod("getPackageInstaller").invoke(pm);

        Class<?> ipi = Class.forName("android.content.pm.IPackageInstaller");
        for (Constructor<?> c : PackageInstaller.class.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            // (IPackageInstaller, String installerPackageName[, String tag], int userId)
            if (p.length < 3 || p[0] != ipi || p[p.length - 1] != int.class) continue;
            Object[] cargs = new Object[p.length];
            cargs[0] = installer;
            for (int i = 1; i < p.length - 1; i++) cargs[i] = "com.android.shell";
            cargs[p.length - 1] = 0;
            c.setAccessible(true);
            return (PackageInstaller) c.newInstance(cargs);
        }
        throw new IllegalStateException("no usable PackageInstaller constructor");
    }
}
