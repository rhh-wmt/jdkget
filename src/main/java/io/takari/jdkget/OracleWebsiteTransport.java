package io.takari.jdkget;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import io.takari.jdkget.JdkReleases.JCE;
import io.takari.jdkget.JdkReleases.JdkBinary;
import io.takari.jdkget.JdkReleases.JdkRelease;

public class OracleWebsiteTransport implements ITransport {

  public static final String ORACLE_WEBSITE = "http://download.oracle.com/otn-pub";

  public static final String JDK_URL_FORMAT = "/java/jdk/%s/jdk-%s-%s.%s";
  public static final String OTN_COOKIE = "oraclelicense=accept-securebackup-cookie";

  private String website;

  public OracleWebsiteTransport() {
    this(ORACLE_WEBSITE);
  }

  public OracleWebsiteTransport(String website) {
    this.website = website;
  }

  private JdkBinary binary(JdkContext context) throws IOException {
    JdkRelease rel = context.getReleases().select(context.getVersion());
    return rel.getBinary(context.getArch());
  }

  private boolean isApple(JdkContext context) {
    // osx jdk6 image really wants to be installed globally and would not work in a separate dir
    //return arch == Arch.OSX_64 && jdkVersion != null && jdkVersion.major == 6 && website.equals(ORACLE_WEBSITE);
    return false;
  }

  @Override
  public File getImageFile(JdkContext context, File parent) throws IOException {
    if (isApple(context)) {
      return new File(parent, "javaforosx.dmg");
    }
    JdkBinary bin = binary(context);
    return new File(parent, new File(bin.getPath()).getName());
  }

  @Override
  public void downloadJdk(JdkContext context, File jdkImage) throws IOException, InterruptedException {

    String url;
    boolean cookie = true;
    if (isApple(context)) {
      // for osx, jdk6* is only available from here
      url = "http://support.apple.com/downloads/DL1572/en_US/javaforosx.dmg";
      cookie = false;
    } else {
      JdkBinary bin = binary(context);
      url = website + "/" + bin.getPath();
    }

    doDownload(url, cookie, jdkImage, context.getOutput());
  }

  @Override
  public void downloadJce(JdkContext context, File jceImage) throws IOException, InterruptedException {
    JCE jce = context.getReleases().getJCE(context.getVersion());
    if (jce == null) {
      throw new IllegalStateException("No JCE for JDK " + context.getVersion());
    }

    doDownload(website + "/" + jce.getPath(), true, jceImage, context.getOutput());
  }

  private void doDownload(String url, boolean cookie, File target, IOutput output) throws IOException, InterruptedException {
    output.info("Downloading " + url);

    // Oracle does some redirects so we have to follow a couple before we win the JDK prize
    URLConnection con;
    int retries = 10;
    for (int retry = 0; retry < retries; retry++) {
      con = new URL(url).openConnection();
      int code = 200;
      String msg = null;

      if (con instanceof HttpURLConnection) {
        HttpURLConnection httpCon = (HttpURLConnection) con;
        if (cookie) {
          httpCon.setRequestProperty("Cookie", OTN_COOKIE);
        }
        code = httpCon.getResponseCode();
        msg = httpCon.getResponseMessage();
      }
      if (code == 200) {
        String contentLength = con.getHeaderField("Content-Length");
        long totalHint = -1;
        if (contentLength != null) {
          try {
            totalHint = Long.parseLong(contentLength);
          } catch (NumberFormatException e) {
          }
        }

        try (InputStream is = con.getInputStream(); OutputStream os = new FileOutputStream(target)) {
          Util.copyWithProgress(is, os, totalHint, output);
        }
        return;
      } else if (code == 301 || code == 302) {
        url = con.getHeaderField("Location");
        output.info("Redirecting to " + url);
      } else {
        output.error("Server responded with " + code + ": " + msg);
      }
    }

    throw new IOException("Could not download jdk after " + retries + " attempts");
  }

  @Override
  public boolean validate(JdkContext context, File jdkImage) throws IOException, InterruptedException {
    if (isApple(context)) {
      return jdkImage.length() == 66724162L;
    } else {
      JdkBinary bin = binary(context);

      IOutput output = context.getOutput();

      int checks = 0;
      int failed = 0;

      if (bin.getSha256() != null) {
        checks++;
        String fileHash = hash(jdkImage, Hashing.sha256());
        if (!bin.getSha256().equals(fileHash)) {
          failed++;
          output.error("File sha256 `" + fileHash + "` differs from `" + bin.getSha256() + "`");
        }
      }
      if (bin.getMd5() != null) {
        checks++;
        String fileHash = hash(jdkImage, Hashing.md5());
        if (!bin.getMd5().equals(fileHash)) {
          failed++;
          output.error("File md5 `" + fileHash + "` differs from `" + bin.getMd5() + "`");
        }
      }
      if (bin.getSize() != -1) {
        checks++;
        if (bin.getSize() != jdkImage.length()) {
          failed++;
          output.error("File size `" + jdkImage.length() + "` differs from `" + bin.getSize() + "`");
        }
      }

      if (checks != 0 && failed > 0) {
        return false;
      }
    }
    return true;
  }

  private static String hash(File f, HashFunction hf) throws IOException {
    Hasher h = hf.newHasher();
    try (InputStream in = new FileInputStream(f)) {
      byte[] buf = new byte[8192];
      int l;
      while ((l = in.read(buf)) != -1) {
        h.putBytes(buf, 0, l);
      }
    }
    return h.hash().toString();
  }

}
