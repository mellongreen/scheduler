package jay;

import javafx.util.Pair;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import static java.lang.System.exit;

public class Main
{
    public static final int MAXPORT = 2;
    public static final int DSTOFFSET = 3600;
    public static final String LENGTHPAT ="^((\\d+)h)?((\\d+)m)?$";
    public static final String TIMEPAT ="^([01]?[0-9]|2[0-3]):([0-5][0-9])$";
    public static final String DATEPAT ="^(\\d+)/(\\d+)$";

    public static final List<Pair<String,String>> optEquivalence = new ArrayList<Pair<String, String>>()
    {{
            add(new Pair<String, String>("d", "date"));
            add(new Pair<String,String>("t","time"));
            add(new Pair<String,String>("l","length"));
            add(new Pair<String,String>("s","show"));
            add(new Pair<String,String>("p","port"));
        }};
    public static final HashMap<Integer,Integer> monthday = new HashMap<Integer,Integer>()
    {{
           put(1, 31);
           put(2, 28);
            put(3, 31);
            put(4, 30);
            put(5, 31);
            put(6, 30);
            put(7, 31);
            put(8, 31);
            put(9, 30);
            put(10, 31);
            put(11, 30);
            put(12, 31);
    }};
    public static boolean isoptValid(HashMap<String,String> argTable,String opt, boolean isLong)
    {
        boolean optexisted = false;
        boolean optallowed = false;
        if(isLong)
        {
             for(int i=0;i<optEquivalence.size();i++)
             {
                 if(optEquivalence.get(i).getValue().equals(opt))
                 {
                     optallowed=true;
                     break;
                 }
             }
            if(argTable.containsKey(opt))optexisted=true;
        }
        else
        {
            String equiname = "";
            for(int i=0;i<optEquivalence.size();i++)
            {
                if(optEquivalence.get(i).getKey().equals(opt))
                {
                    equiname = optEquivalence.get(i).getValue();
                    optallowed=true;
                    break;
                }
            }
            if(argTable.containsKey(equiname))optexisted=true;
        }
        return  optallowed && !optexisted;
    }
    public static void process(HashMap<String,String> argTable)
    {
        if(!argTable.containsKey("time"))
        {
            System.err.println("time must be specified");
            System.exit(1);
        }
        if(!argTable.containsKey("length"))
        {
            System.err.println("length must be specified");
            System.exit(1);
        }
        if(!argTable.containsKey("port"))
        {
            System.err.println("port must be specified");
            System.exit(1);
        }
        if(!argTable.containsKey("date"))
        {
            argTable.put("date","today");
        }

        Set<String> args = argTable.keySet();
        StringBuilder builder = new StringBuilder();
        boolean show = false;
        long begin = 0;
        for(String x: args)
        {
            if (x.equals("date"))
            {
                if (show) throw new IllegalArgumentException("`show` can only appear by itself");
                if (argTable.get(x).isEmpty()) throw new IllegalArgumentException("time must not be empty");
                if (argTable.get(x).equals("today"))
                {
                    begin += LocalDate.now().toEpochDay()*24*60*60;
                }
                else if (argTable.get(x).equals("tmr"))
                {
                    begin += (LocalDate.now().toEpochDay()+1)*24*60*60;
                }
                else
                {
                    Pattern patx = Pattern.compile(DATEPAT);
                    Matcher mx = patx.matcher(argTable.get(x));
                    if (mx.find())
                    {
                        int date = Integer.parseInt(mx.group(1));
                        int month = Integer.parseInt(mx.group(2));
                        if(!(1<=month && month<=12))
                        {
                            System.err.println("Illegal date month (1-12)");
                            exit(1);
                        }
                        int maxalloweddays = monthday.get(month);
                        if(LocalDate.now().isLeapYear() && month==2)
                        {
                            maxalloweddays++;
                        }
                        if(date <= 0||date>maxalloweddays)
                        {
                            System.err.println("Illegal day ("+ maxalloweddays+")");
                            exit(1);
                        }
                        begin +=LocalDate.of(LocalDate.now().getYear(),month,date).toEpochDay()*24*60*60;
                    }
                    else
                    {
                        System.err.println("Illegal date format (dd/MM)");
                        exit(1);
                    }
                }
            }
            else if (x.equals("time"))
            {
                if (show) throw new IllegalArgumentException("`show` can only appear by itself");
                if (argTable.get(x).isEmpty()) throw new IllegalArgumentException("time must not be empty");
                if (argTable.get(x).equals("INDEF"))
                {
                    System.err.println("Indefinite length is not yet supported");
                    System.exit(1);
                }
                else
                {
                    Pattern patx = Pattern.compile(TIMEPAT);
                    Matcher mx = patx.matcher(argTable.get(x));
                    if (mx.find())
                    {
                        String hour = mx.group(1);
                        String min = mx.group(2);
                        if (hour != null) {
                            begin += Integer.parseInt(hour) * 3600;
                        }
                        if (min != null) {
                            begin += Integer.parseInt(min) * 60;
                        }
                    }
                    else
                    {
                        System.err.println("Illegal time format (hh:mm)");
                        exit(1);
                    }
                }
            }
            else if (x.equals("length"))
            {
                try {
                    if (show) throw new IllegalArgumentException("`show` can only appear by itself");
                    if (argTable.get(x).isEmpty()) throw new IllegalArgumentException("length must not be empty");
                    Pattern pat = Pattern.compile(LENGTHPAT);
                    Matcher m = pat.matcher(argTable.get(x));
                    if (m.find())
                    {
                        int sec = 0;
                        String hour = m.group(2);
                        String min = m.group(4);
                        if (hour != null) {
                            sec += Integer.parseInt(hour) * 3600;
                        }
                        if (min != null) {
                            sec += Integer.parseInt(min) * 60;
                        }
                        builder.append(x);
                        builder.append(sec);
                        builder.append(';');
                    }
                    else
                    {
                        throw new IllegalArgumentException("Illegal length format ([n*h][n*m])");
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Invalid length value");
                    exit(1);
                } catch (IllegalArgumentException e) {
                    System.err.println(e.toString());
                    exit(1);
                }
            }
            else if (x.equals("show"))
            {
                builder.append(x);
                builder.append(';');
                show = true;
            }
            else if (x.equals("port"))
            {
                try {
                    if (show) throw new IllegalArgumentException("`show` can only appear by itself");
                    int t = Integer.parseInt(argTable.get(x));
                    if (t <= 0 || t > MAXPORT) throw new IllegalArgumentException("Port does not exist (1-"+MAXPORT+")");
                    builder.append(x);
                    builder.append(argTable.get(x));
                    builder.append(';');
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number");
                    exit(1);
                } catch (IllegalArgumentException e) {
                    System.err.println(e.toString());
                    exit(1);
                }

            }
            else
            {
                System.err.println("Invalid argTable");
                exit(1);
            }
        }
        TimeZone tz = Calendar.getInstance().getTimeZone();

        begin -= tz.getOffset(begin)/1000;
        Date xc = new Date(begin*1000);
        if(tz.inDaylightTime(xc))
            begin -= DSTOFFSET;
        builder.append("begin");
        builder.append(begin);
        builder.append(';');
        CRC32 crc32 = new CRC32();
        crc32.update(builder.toString().getBytes());
        builder.insert(0,crc32.getValue());

        System.out.println(builder.toString());
    }
    public static void main(String[] args) {
        HashMap<String, String> argTable = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i].charAt(0)) {
                case '-':
                    if (args[i].length() < 2) {
                        System.err.println("`" + args[i] + "`" + " is not a valid option");
                        exit(1);
                    }
                    if (args[i].charAt(1) == '-') {
                        if (args[i].length() < 3) {
                            System.err.println("`" + args[i] + "`" + " is not a valid option");
                            exit(1);
                        }
                        if (isoptValid(argTable, args[i].substring(2), true)) {
                            if (args[i].substring(2).equals("show")) {
                                argTable.put(args[i].substring(2), "");
                            } else {
                                if (args.length - 1 == i) {
                                    System.err.println("`" + args[i] + "`" + " is missing an argument");
                                    exit(1);
                                }
                                argTable.put(args[i].substring(2), args[i + 1]);
                                i++;
                            }
                        } else {
                            System.err.println("`" + args[i] + "`" + " produce invalid combination");
                            exit(1);
                        }
                    } else {
                        if (isoptValid(argTable, args[i].substring(1), false)) {
                            String equiname = "";
                            for (int j = 0; j < optEquivalence.size(); j++) {
                                if (optEquivalence.get(j).getKey().equals(args[i].substring(1))) {
                                    equiname = optEquivalence.get(j).getValue();
                                    break;
                                }
                            }
                            if (equiname.equals("show")) {
                                argTable.put(equiname, "");
                            } else {
                                if (args.length - 1 == i) {
                                    System.err.println("`" + args[i] + "`" + " is missing an argument");
                                    exit(1);
                                }
                                argTable.put(equiname, args[i + 1]);
                                i++;
                            }
                        } else {
                            System.err.println("`" + args[i] + "`" + " produce invalid combination");
                            exit(1);
                        }
                    }
                    break;
                default:
                    System.err.println("`" + args[i] + "`" + " is missing argument context");
                    exit(1);
                    break;
            }

        }
        process(argTable);
    }
}
