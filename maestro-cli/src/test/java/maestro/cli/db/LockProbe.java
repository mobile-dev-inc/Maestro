package maestro.cli.db;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

public class LockProbe {
    public static void main(String[] args) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(args[0], "rw")) {
            FileLock lock = file.getChannel().tryLock();
            if (lock == null) {
                System.out.println("BLOCKED");
            } else {
                try {
                    System.out.println("ACQUIRED");
                } finally {
                    lock.release();
                }
            }
        }
    }
}
