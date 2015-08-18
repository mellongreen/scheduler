/*
java scheduler for upper sys
Copyright (C) 2015 Mellon Green

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

import gnu.io.CommPortIdentifier;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;
import javafx.util.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
//import java.util.logging.ConsoleHandler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import static java.lang.Math.round;
import static java.lang.System.exit;

public class Scheduler
{
    public static final int MAXPORT = 2;
    public static final int DSTOFFSET = 3600;
    public static final int DATARATE = 14400;
    public static final int MAXATTEMPT = 3;


    public static final String LENGTHPAT ="^((\\d+)h)?((\\d+)m)?$";
    public static final String TIMEPAT ="^([01]?[0-9]|2[0-3]):([0-5][0-9])$";
    public static final String DATEPAT ="^(\\d+)/(\\d+)$";
    public static final String MSGPAT ="^(.*);(.*);(ts\\d+);$";
    public static final String RESPONSEREPPAT = "(.*)\\*(.*)\\*(.*)";
    public static boolean sync = false;
    public static boolean show = false;

    public static final List<Pair<String,String>> optEquivalence = new ArrayList<Pair<String, String>>()
    {{
            add(new Pair<String, String>("d", "date"));
            add(new Pair<String,String>("t","time"));
            add(new Pair<String,String>("l","length"));
            add(new Pair<String,String>("s","show"));
            add(new Pair<String,String>("p","port"));
            add(new Pair<String,String>("h","help"));
            add(new Pair<String,String>("y","sync"));
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
    public static void sendcmd(String cmd,String hash)
    {
        Enumeration portlistEnum = CommPortIdentifier.getPortIdentifiers();
        List<Pair<String,CommPortIdentifier>> serialPorts = new ArrayList<Pair<String,CommPortIdentifier>>();
        while (portlistEnum.hasMoreElements())
        {
            CommPortIdentifier x = (CommPortIdentifier) portlistEnum.nextElement();
            if (x.getPortType() == CommPortIdentifier.PORT_SERIAL)
            {
                serialPorts.add(new Pair<String,CommPortIdentifier>(x.getName(),x));
            }
        }

        if(serialPorts.isEmpty())
        {
            System.out.println("No available serial port detected");
            System.exit(0);
        }
        else
        {
            if(serialPorts.size()==1)
            {
                System.out.println(serialPorts.size() + " port detected");
                System.out.println("selecting " + serialPorts.get(0).getKey()+" ...");
                if (communicate(serialPorts.get(0).getValue(),cmd,hash))System.out.println("Done");
                else System.out.println("Fail");
            }
            else
            {
                System.out.println(serialPorts.size() + " ports detected");
                System.out.print("select from ");
                StringBuilder builder = new StringBuilder();
                for (Pair<String,CommPortIdentifier> x : serialPorts)
                {
                    builder.append(x.getKey());
                    builder.append(',');
                }
                builder.deleteCharAt(builder.length()-1);
                System.out.print(builder.toString()+"\n");

                Scanner reader = new Scanner(System.in);
                while(true)
                {
                    String port;
                    System.out.print("Specify Arduino port :");
                    port = reader.nextLine();
                    for(int i=0;i<serialPorts.size();i++)
                    {
                        if(port.equals(serialPorts.get(i).getKey()))
                        {
                            System.out.println("selecting " + port+" ...");
                            if (communicate(serialPorts.get(i).getValue(),cmd,hash))System.out.println("Done");
                            else System.out.println("Fail");
                            return;
                        }
                    }
                    System.out.println("invalid port");
                }
            }
        }
    }
    public static boolean communicate(CommPortIdentifier x,String payload,String hash)
    {
        try
        {
            String[] t =payload.split(";");
            String ts = t[t.length-1];
            SerialPort port = (SerialPort)x.open("scheduler", 2000);
            //port.setDTR(true);
            port.setSerialPortParams(DATARATE,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);
            Thread.sleep(2200);
            BufferedReader input = new BufferedReader(new InputStreamReader(port.getInputStream()));
            OutputStream output = port.getOutputStream();
            long tsbegin;
            StringBuilder builder =  new StringBuilder();
            CRC32 crc = new CRC32();
            int attempt =0;
            tsbegin = System.currentTimeMillis();
            Pattern patx = Pattern.compile(MSGPAT);

            while ((System.currentTimeMillis()-tsbegin)<1000 )
            {
                attempt++;
                builder.setLength(0);
                crc.reset();
                System.out.print("Attempt #");
                System.out.println(attempt);

                if(sync)
                {
                    builder.append(payload);
                    //builder.deleteCharAt(builder.length()-1);
                    //builder.append();
                    builder.append("systime");
                    builder.append(round(System.currentTimeMillis() / 1000.0));
                    builder.append(';');
                    crc.update(builder.toString().getBytes());

                    output.write(String.format("%08X", crc.getValue()).getBytes());
                    output.write(builder.toString().getBytes());
                }
                else
                {
                    output.write(hash.getBytes());
                    output.write(payload.getBytes());
                }
                output.write('\n');
                output.flush();

                String response;
                String body;

                    //CRC32 crc32 = new CRC32();
                 crc.reset();
                long receivedhash;
                System.out.println("waiting for input");
                while(!input.ready());
                    response = input.readLine();
                    if(response.length()<=10){
                        System.out.println("Corruption during transfer");
                        if(attempt>=MAXATTEMPT)
                        {
                            input.close();
                            output.close();
                            port.close();
                            System.out.println("Giving up ...");
                            return false;
                        }
                        continue;
                    }

                    receivedhash = Long.parseLong(response.substring(0,8),16);
                    body = response.substring(8,response.length());
                    crc.update(body.getBytes());
                    if(crc.getValue()!=receivedhash)
                    {
                        System.out.println("Corruption during transfer");
                        if(attempt>=MAXATTEMPT)
                        {
                            input.close();
                            output.close();
                            port.close();
                            System.out.println("Giving up ...");
                            return false;
                        }
                        continue;
                    }
                    Matcher mx = patx.matcher(body);
                    if (mx.find() && mx.group(3).equals(ts))
                    {
                            System.out.println(processResponse(mx.group(2)));
                            input.close();
                            output.close();
                            port.close();
                            if(mx.group(1).equals("ack"))return true;
                            else if(mx.group(1).equals("nak"))return false;
                            else
                            {
                                System.out.println("ACK not received");
                                if(attempt>=MAXATTEMPT)
                                {
                                    input.close();
                                    output.close();
                                    port.close();
                                    System.out.println("Giving up ...");
                                    return false;
                                }
                            }
                    }
                    else
                    {
                        System.out.println("Illegal response received");
                        if(attempt>=MAXATTEMPT)
                        {
                            input.close();
                            output.close();
                            port.close();
                            System.out.println("Giving up ...");
                            return false;
                        }
                    }


            }
            input.close();
            output.close();
            port.close();
            System.out.println("Timeout");
            return false;
        }
        catch (PortInUseException e)
        {
            System.err.println("Selected port is busy");
            return false;
        } catch (UnsupportedCommOperationException e) {
            System.err.println("Unsupported Serial port setting");
            return false;
        } catch (InterruptedException e) {
            System.err.println("Interrupted");
            return false;
        } catch (IOException e) {
            System.err.println("I/O failed");
            return false;
        }
    }
    public static String processResponse(String x)
    {
        Pattern pat = Pattern.compile(RESPONSEREPPAT);
        StringBuilder builder = new StringBuilder();
        String xxx[] = x.split("\\\\");
        Matcher y;
        for(String line:xxx)
        {
            y = pat.matcher(line);
            if (y.find())
            {
                builder.append(y.group(1));
                Date date = new Date(Integer.parseInt(y.group(2)) * 1000L);
                DateFormat format = new SimpleDateFormat("dd/MM HH:mm");
                format.setTimeZone(Calendar.getInstance().getTimeZone());
                builder.append(format.format(date));
                builder.append(y.group(3));
            }
            else builder.append(line);
            builder.append('\n');
        }
        if(builder.length()!=0)builder.deleteCharAt(builder.length()-1);
        return builder.toString();
    }
    public static byte[] hexToByteArray(String s)
    {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
    public static void process(HashMap<String,String> argTable) {

        if(!argTable.containsKey("sync") && !argTable.containsKey("help") && !argTable.containsKey("show"))
        {
            if (!argTable.containsKey("time")) {
            System.err.println("time must be specified");
            System.exit(1);
        }
        if (!argTable.containsKey("length")) {
            System.err.println("length must be specified");
            System.exit(1);
        }
        if (!argTable.containsKey("port")) {
            System.err.println("port must be specified");
            System.exit(1);
        }
        if (!argTable.containsKey("date")) {
            argTable.put("date", "today");
        }
        }

        if (argTable.containsKey("show") && argTable.size() != 1)
        {
            System.err.println("`show` can only appear by itself");
            return;
        }
        if(argTable.containsKey("help"))
        {
            if(argTable.size()==1)
            {
                System.out.print("-d --date        date (dd/MM or 'today' or 'tmr')\n" +
                        "-t --time        time (hh:mm)\n" +
                        "-l --length      length of time for which power should be on (*h*m)\n" +
                        "-s --show        display all registered schedules\n" +
                        "-p --port        power port\n" +
                        "-h --help        display this page\n" +
                        "-y --sync        sync unit with system time");

            }
            else {
                System.err.println("`help` can only appear by itself");
            }
            return;
        }

        StringBuilder builder = new StringBuilder();
        if(argTable.containsKey("sync"))
        {
            if(argTable.size()==1)
            {
                sync=true;
                builder.append("syn");
                builder.append(';');
            }
            else
            {
                System.err.println("`sync` can only appear by itself");
                return;
            }
        }
        else if (argTable.containsKey("show"))
        {
            show=true;
            builder.append("shw");
            builder.append(';');
        }
        else
        {
            builder.append("sch");
            builder.append(';');
        }
        Set<String> args = argTable.keySet();

        long begin = 0;
        for(String x: args)
        {
            if (x.equals("date"))
            {
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
            //    builder.append(x);
             //   builder.append(';');
            }
            else if (x.equals("port"))
            {
                try {
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
            else if (x.equals("sync"))
            {
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
        if(!sync && !show)
        {
            builder.append("begin");
            if(begin <= (System.currentTimeMillis()/1000)+ 5 )
            {
                System.err.println("Schedule is either too close to present or in the past");
                System.exit(1);
            }
            builder.append(begin);
            builder.append(';');
        }
        builder.append("ts");
        builder.append(System.currentTimeMillis());
        builder.append(';');
        //builder.append('\n');

        CRC32 crc32 = new CRC32();
        crc32.update(builder.toString().getBytes());
        //   builder.insert(0, crc32.getValue());
//        byte[] hash = hexToByteArray(String.format("%08X", crc32.getValue()));

        sendcmd(builder.toString(),String.format("%08X", crc32.getValue()));
    }
    public static void main(String[] args) {
        sync=false;
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
                            }
                            else if (args[i].substring(2).equals("help")) {
                                argTable.put(args[i].substring(2), "");
                            }
                            else if (args[i].substring(2).equals("sync")) {
                                argTable.put(args[i].substring(2), "");
                            }
                            else {
                                if (args.length - 1 == i) {
                                    System.err.println("`" + args[i] + "`" + " is missing an argument");
                                    exit(1);
                                }
                                argTable.put(args[i].substring(2), args[i + 1]);
                                i++;
                            }
                        } else {
                            System.err.println("`" + args[i] + "`" + " is not a valid argument or produces invalid combination");
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
                            }
                            else if(equiname.equals("help")){
                                argTable.put(equiname, "");
                            }
                            else if(equiname.equals("sync")){
                                argTable.put(equiname, "");
                            }
                            else {
                                if (args.length - 1 == i) {
                                    System.err.println("`" + args[i] + "`" + " is missing an argument");
                                    exit(1);
                                }
                                argTable.put(equiname, args[i + 1]);
                                i++;
                            }
                        } else {
                            System.err.println("`" + args[i] + "`" + " is not a valid argument or produces invalid combination");
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
