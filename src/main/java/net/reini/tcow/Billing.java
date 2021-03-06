package net.reini.tcow;

import static com.sun.mail.smtp.SMTPMessage.NOTIFY_FAILURE;
import static com.sun.mail.smtp.SMTPMessage.RETURN_HDRS;
import static java.lang.String.format;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.time.format.DateTimeFormatter.ofPattern;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage.RecipientType;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.itextpdf.forms.PdfPageFormCopier;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.sun.mail.smtp.SMTPMessage;

import ch.codeblock.qrinvoice.FontFamily;
import ch.codeblock.qrinvoice.OutputFormat;
import ch.codeblock.qrinvoice.PageSize;
import ch.codeblock.qrinvoice.QrInvoicePaymentPartReceiptCreator;
import ch.codeblock.qrinvoice.model.QrInvoice;
import ch.codeblock.qrinvoice.model.ReferenceType;
import ch.codeblock.qrinvoice.model.builder.QrInvoiceBuilder;
import ch.codeblock.qrinvoice.output.PaymentPartReceipt;
import ch.codeblock.qrinvoice.util.CreditorReferenceUtils;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;

public class Billing implements AutoCloseable {
  private static final String RECHNUNG = "Rechnung";

  public static void main(String[] args) {
    int argsLength = args.length;
    if (argsLength > 0) {
      Path dataDir = Paths.get(args[0]);
      try (Billing billing = new Billing(dataDir)) {
        if (argsLength > 1) {
          Map<String, Object> row = new HashMap<>();
          row.put("Bezahlt", "");
          row.put("Mailed", "");
          row.put("Email", args[1]);
          for (int idx = 2; idx < argsLength; idx++) {
            String[] parts = args[idx].split("=", 2);
            row.put(parts[0], parts[1]);
          }
          billing.processDocument(row);
        } else {
          billing.readBillingInformation(dataDir.resolve("Rechnungsliste_Budget.ods"),
              billing::processDocument);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    } else {
      System.out.println("Usage: TCOW <datadirectory> [<targetEmail> [<rowkey=rowvalue> ..]]");
    }
  }

  private final Logger logger;
  private final LocalDateTime date;
  private final Session mailSession;
  private final DateTimeFormatter dateFormatter;
  private final DateTimeFormatter yearFormatter;
  private final Path dataDir;
  private final InternetAddress sender;
  private final Pattern replacementPattern;
  private final Properties mailProperties;
  private final PdfPageFormCopier formCopier;

  private PdfDocument pdfConcatenated;

  public Billing(Path dataDir) throws IOException {
    this.dataDir = dataDir;
    logger = LoggerFactory.getLogger(getClass());
    date = LocalDateTime.now();
    dateFormatter = ofPattern("dd.MM.yyyy", Locale.GERMAN);
    yearFormatter = ofPattern("yyyy", Locale.GERMAN);
    sender = new InternetAddress("patrick@reini.net", "Patrick Reinhart");
    replacementPattern = Pattern.compile("\\$\\{([\\w]+)\\}");
    mailProperties = new Properties();
    formCopier = new PdfPageFormCopier();
    Path mailPropertiesFile = Paths.get("mail.properties");
    if (exists(mailPropertiesFile)) {
      try (InputStream in = newInputStream(mailPropertiesFile)) {
        mailProperties.load(in);
      }
    } else {
      logger.info("{} not available. Using defaults.", mailPropertiesFile);
    }
    mailSession = Session.getDefaultInstance(mailProperties);
  }

  @Override
  public void close() throws Exception {
    if (pdfConcatenated != null) {
      pdfConcatenated.close();
    }
  }

  Map<String, Object> processDocument(Map<String, Object> row) {
    try {
      Path pdfFile = createDirectories(dataDir.resolve(RECHNUNG + "en"))
          .resolve(format("%s_%s_%s.pdf", RECHNUNG, get("Vorname", row), get("Name", row))
              .replace(' ', '_'));
      if (!exists(pdfFile)) {
        row.put("QrInvoice", Base64.getEncoder().encodeToString(createQrInvoice(row)));
        createPdf(pdfFile, row);
      }
      String email = get("Email", row);
      if (email.isEmpty()) {
        sendEmail(pdfFile);
      } else if (get("Mailed", row).isEmpty()) {
        return sendEmail(row, pdfFile);
      }
    } catch (Exception e) {
      logger.error("Error processing PDF", e);
    }
    return Collections.emptyMap();
  }

  void sendEmail(Path pdfFile) throws IOException {
    try (InputStream in = newInputStream(pdfFile)) {
      if (pdfConcatenated == null) {
        pdfConcatenated = new PdfDocument(
            new PdfWriter(newOutputStream(dataDir.resolve(RECHNUNG + "enToPrint.pdf"))));
      }
      try (PdfDocument pdfDocument = new PdfDocument(new PdfReader(in))) {
        pdfDocument.copyPagesTo(1, pdfDocument.getNumberOfPages(), pdfConcatenated, formCopier);
      }
    }
  }

  void createPdf(Path pdfFile, Map<String, Object> row) throws IOException, JRException {
    logger.info("Creating PDF {}", pdfFile);
    try (OutputStream pdfOut = newOutputStream(pdfFile)) {
      JasperReport jasperReport = JasperCompileManager.compileReport("report.jrxml");
      JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, new HashMap<>(),
          new SingleRowDataSource((key, valueType) -> getTypeValue(row, key, valueType)));
      JasperExportManager.exportReportToPdfStream(jasperPrint, pdfOut);
    }
  }

  byte[] createQrInvoice(Map<String, Object> row) {
    final QrInvoice qrInvoice = QrInvoiceBuilder.create() //
        .creditorIBAN("CH92 8080 8005 2401 3817 1") // CH44 3199 9123 0008 8901 2 
        .paymentAmountInformation(p -> p.chf(new BigDecimal(get("Betrag", row)))) //
        .creditor(c -> c //
            .structuredAddress() //
            .name("Tauchclub Obwalden") //
            .streetName("Flüelistrasse") //
            .houseNumber("63") //
            .postalCode("6064") //
            .city("Kerns") //
            .country("CH") //
        ) //
        .ultimateDebtor(d -> d //
            .structuredAddress() //
            .name(format("%s %s", get("Vorname", row), get("Name", row))) //
            .streetName(get("Strasse", row)) //
            .houseNumber(get("Nr", row)) //
            .postalCode(get("PLZ", row)) //
            .city(get("Ort", row)) //
            .country("CH") //
        ) //
        .paymentReference(r -> r //
//            .referenceType(ReferenceType.QR_REFERENCE) //
//            .reference(QRReferenceUtils.createQrReference(get("R#", row))) //
            .referenceType(ReferenceType.CREDITOR_REFERENCE) //
            .reference(CreditorReferenceUtils.createCreditorReference(get("R#", row)))
        ).build(); //
    final PaymentPartReceipt paymentPartReceipt = QrInvoicePaymentPartReceiptCreator //
        .create() //
        .qrInvoice(qrInvoice) //
        .outputFormat(OutputFormat.PNG) //
        .pageSize(PageSize.DIN_LANG) //
        .fontFamily(FontFamily.LIBERATION_SANS) // or HELVETICA, ARIAL
        .locale(Locale.GERMAN) //
        .createPaymentPartReceipt(); //
    return paymentPartReceipt.getData();
  }

  private Object getTypeValue(Map<String, Object> row, String key, Class<?> valueType) {
    String value = get(key, row);
    if (String.class.equals(valueType)) {
      return value;
    } else if (value.isEmpty()) {
      return null;
    } else if (Integer.class.equals(valueType)) {
      return Integer.valueOf(value);
    } else if (BigDecimal.class.equals(valueType)) {
      return new BigDecimal(value);
    } else if (Date.class.equals(valueType)) {
      return Date.from(dateFormatter.parse(value, LocalDate::from)
          .atStartOfDay(ZoneId.systemDefault()).toInstant());
    } else if (InputStream.class.equals(valueType)) {
      return new ByteArrayInputStream(Base64.getDecoder().decode(value));
    }
    return null;
  }

  void readBillingInformation(Path rechnungsListeOds,
      Function<Map<String, Object>, Map<String, Object>> rowFunction) throws Exception {
    final AtomicBoolean hasChanged = new AtomicBoolean();
    SpreadSheet spreadSheet = SpreadSheet.createFromFile(rechnungsListeOds.toFile());
    try {
      Sheet sheet = spreadSheet.getSheet(0);
      List<String> keys = new ArrayList<>();
      final Map<String, Integer> columnNameMap = new HashMap<>();
      for (int x = 0, mx = sheet.getColumnCount(); x < mx; x++) {
        String keyName = sheet.getCellAt(x, 0).getTextValue();
        if (keyName.isEmpty()) {
          break;
        }
        keys.add(keyName);
        columnNameMap.put(keyName, Integer.valueOf(x));
      }
      for (int y = 1, n = sheet.getRowCount(); y < n; y++) {
        String anrede = sheet.getCellAt(0, y).getTextValue();
        if (anrede.isEmpty()) {
          break;
        }
        Map<String, Object> rowValues = new LinkedHashMap<>();
        rowValues.put(keys.get(0), anrede);
        for (int x = 1, mx = keys.size(); x < mx; x++) {
          rowValues.put(keys.get(x), sheet.getCellAt(x, y).getTextValue());
        }
        final int row = y;
        Map<String, Object> changedRows = rowFunction.apply(rowValues);
        changedRows.forEach((key, value) -> {
          Integer col = columnNameMap.get(key);
          if (col != null) {
            sheet.setValueAt(value, col.intValue(), row);
            hasChanged.set(true);
          }
        });
      }
    } finally {
      if (hasChanged.get()) {
        spreadSheet.saveAs(dataDir.resolve("Rechnungsliste_Budget_new.ods").toFile());
      }
    }
  }

  Map<String, Object> sendEmail(Map<String, Object> row, Path pdfFile)
      throws MessagingException, IOException {
    String email = get("Email", row);
    String email2 = get("Email2", row);
    String vorname = get("Vorname", row);
    String nachname = get("Name", row);
    String emailBody = getEmailBody(row);

    InternetAddress recipient = new InternetAddress(email, format("%s %s", vorname, nachname));
    Multipart mp = new MimeMultipart();

    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setText(emailBody, "ISO-8859-1");

    mp.addBodyPart(htmlPart);

    // Part two is attachment
    MimeBodyPart attachment = new MimeBodyPart();
    DataSource source = new ByteArrayDataSource(readAllBytes(pdfFile), "application/pdf");
    attachment.setDataHandler(new DataHandler(source));
    attachment.setFileName(RECHNUNG + ".pdf");
    mp.addBodyPart(attachment);

    SMTPMessage msg = new SMTPMessage(mailSession);
    msg.setReturnOption(RETURN_HDRS);
    msg.setNotifyOptions(NOTIFY_FAILURE);
    msg.setFrom(sender);
    msg.addRecipient(RecipientType.TO, recipient);
    if (!email2.isEmpty()) {
      msg.addRecipient(RecipientType.CC, new InternetAddress(email2));
    }
    msg.setSubject("TCOW Jahresbeitrag ".concat(get("Jahr", row)));
    msg.setContent(mp);
    logger.info("Sending document to {}", recipient);
    Transport.send(msg, mailProperties.getProperty("mail.user"),
        mailProperties.getProperty("mail.password"));
    Instant instant = date.toLocalDate().atStartOfDay().atZone(ZoneId.of("UTC")).toInstant();
    return Collections.singletonMap("Mailed", Date.from(instant));
  }

  String getEmailBody(Map<String, Object> row) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(getEmailResource(row))) {
      return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
          .map(line -> mapReplacements(line, row)).collect(Collectors.joining("\n"));
    }
  }

  String getEmailResource(Map<String, Object> row) {
    if (get("Bezahlt", row).isEmpty()) {
      return "BillingEmailTemplate.txt";
    }
    return "BillPayedEmailTemplate.txt";
  }

  String mapReplacements(String input, Map<String, Object> row) {
    StringBuffer result = new StringBuffer();
    Matcher matcher = replacementPattern.matcher(input);
    while (matcher.find()) {
      matcher.appendReplacement(result, get(matcher.group(1), row));
    }
    return matcher.appendTail(result).toString();
  }

  String get(String key, Map<String, Object> row) {
    return String.valueOf(row.computeIfAbsent(key, k -> {
      switch (k) {
        case "Jahr":
          return date.format(yearFormatter);
        default:
          return "<" + key + ">";
      }
    }));
  }
}
