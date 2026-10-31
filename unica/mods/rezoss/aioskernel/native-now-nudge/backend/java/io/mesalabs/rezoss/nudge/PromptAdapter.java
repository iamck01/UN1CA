package io.mesalabs.rezoss.nudge;
import java.util.regex.*;
/** Translate the known Gauss contextual-reply template to a short Qwen instruction. */
public final class PromptAdapter {
 private static final String NO_LABELS="Reply in the requested language and match the conversation's writing system. If a non-English language is typed with English/Latin letters, reply in that language using Latin letters; do not translate it into English or switch scripts. Mirror mixed-language messages naturally. Output reply text only. Never add tone labels such as Positive:, Uncertain:, Negative:, or speaker labels such as Me:, User:, Assistant: before or after any reply.";
 public static String adapt(String raw){
  String dialogue=section(raw,"dialogue"),records=section(raw,"app_records");
  Matcher language=Pattern.compile("Output only the three responses in ([^\\r\\n,]+), separated by line breaks").matcher(raw);
  if(raw.contains("generate three natural responses")&&dialogue!=null&&language.find()){
   StringBuilder prompt=new StringBuilder("Suggest three short text messages I can send back, in ").append(language.group(1).trim()).append(".\nConversation:\n");
   prompt.append(dialogue.trim().replace("Others:","Friend:").replace("User:","Me:"));
   if(records!=null&&!records.trim().isEmpty()&&!records.trim().equals("[]"))prompt.append("\nRelevant context:\n").append(records.trim());
   prompt.append("\nWrite one positive reply, one uncertain reply, and one negative reply appropriate to the conversation. Do not repeat the question. Output only the three replies on separate lines. ").append(NO_LABELS).append("\n/no_think");
   return prompt.toString();
  }
  return raw.replace("<|begin_of_text|>","").replace("<|turn_start|>System","")
   .replace("<|turn_start|>User","").replace("<|turn_start|>Assistant","").replace("<|turn_end|>","").trim()+"\n"+NO_LABELS+"\n/no_think";
 }
 private static String section(String raw,String name){
  String begin="<"+name+">",end="</"+name+">";int first=raw.indexOf(begin),last=raw.lastIndexOf(end);
  return first>=0&&last>=first+begin.length()?raw.substring(first+begin.length(),last):null;
 }
}
