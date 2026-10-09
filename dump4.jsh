var s = com.menghuan.app.data.AiPrompts.INSTANCE.getDEFAULT_ASSISTANT_PROMPT();
java.nio.file.Files.write(java.nio.file.Paths.get("X:/logs/manual-v077.txt"), s.getBytes("UTF-8"));
System.out.println("LEN=" + s.length());
/exit
