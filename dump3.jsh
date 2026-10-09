var f = com.menghuan.app.data.AiPrompts.class.getDeclaredField("DEFAULT_ASSISTANT_PROMPT");
f.setAccessible(true);
var s = (String) f.get(null);
java.nio.file.Files.write(java.nio.file.Paths.get("X:/logs/manual-v076.txt"), s.getBytes("UTF-8"));
System.out.println("LEN=" + s.length());
/exit
