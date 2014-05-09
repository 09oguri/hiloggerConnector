package jp.ac.titech.cs.de.hilogger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiLoggerConnector {
	private static Logger logger = LoggerFactory
			.getLogger(HiLoggerConnector.class);

	// 計測するチャンネル数、ユニット数
	// 変更する場合はロガーユーティリティでメモリハイロガーを再設定する必要がある
	// ユニット毎に別々のチャンネル数を設定できない
	private static final int MAX_CH = 14; // 最大14ch
	private static final int MAX_UNIT = 6; // 最大6unit

	private final static Properties config = new Properties();

	private String hostname;
	private int port;
	private long measurementInterval;
	private long takeInterval;

	private Date startTime;
	private Date endTime;
	private boolean isConnecting;
	private int dataLength;
//	private ArrayList<ArrayList<Double>> volt = new ArrayList<ArrayList<Double>>(); // 取得した電圧
//	private ArrayList<ArrayList<Double>> power = new ArrayList<ArrayList<Double>>(); // 電圧から計算した消費電力
	private ArrayList<DrivePowerResult> results = new ArrayList<DrivePowerResult>();
	private PowerLogger lpt = new PowerLogger();

	/*
	 * ロック用オブジェクト
	 */
	private Object lock = new Object();

	private Socket socket;
	private InputStream is;
	private InputStreamReader isr;
	private BufferedReader br;
	private BufferedInputStream bis;
	private OutputStream os;

	public HiLoggerConnector(String configFilePath) {
		try {
			config.load(new FileInputStream(configFilePath));

			this.hostname = config.getProperty("hilogger.info.hostname");
			this.port = Integer.parseInt(config
					.getProperty("hilogger.info.port"));
			this.measurementInterval = Long.parseLong(config
					.getProperty("hilogger.info.measurementInterval"));
			this.takeInterval = Long.parseLong(config
					.getProperty("hilogger.info.takeInterval"));

//			for (int i = 0; i < MAX_UNIT; i++) {
//				this.volt.add(new ArrayList<Double>());
//				this.power.add(new ArrayList<Double>());
//			}
			int maxDriveNum = MAX_CH / 2 * MAX_UNIT;
			for(int i = 0; i < maxDriveNum; i++) {
				this.results.add(new DrivePowerResult());
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	public void start() {
		System.out.println("hostname: " + hostname + ", port: " + port
				+ ", measurementInterval: " + measurementInterval);
		startConnection();

		Calendar cal = Calendar.getInstance();
		startTime = cal.getTime();

		System.out.println("start time: " + startTime);

		lpt.start();
	}

	class PowerLogger extends Thread {
		public void run() {
			long sumNumOfData = 0L;
			long numOfData = takeInterval / measurementInterval;

			synchronized (lock) {
				while (isConnecting) {
					long before = System.currentTimeMillis();
					byte[] rec = command(Command.REQUIRE_DATA); // データ要求コマンド
					Response res = new Response(rec);

					for (int i = 0; i < numOfData; i++) {
						getData();
					}

					// ログ書き込み
//					boolean carry = false;
//					for (int unit = 0; unit < MAX_UNIT; unit++) {
//						for (int disk = 0; disk < MAX_CH / 2; disk++) {
//							synchronized (this) {
//								if (carry) {
//									disk++;
//									carry = false;
//								}
//								int driveId = unit * MAX_UNIT + disk;
//								logger.info("{},{},{}", before, driveId, power
//										.get(unit).get(0));
//								power.get(unit).remove(0);
//							}
//						}
//						carry = true;
//					}
					
					for(int driveId = 0; driveId < results.size(); driveId++) {
						logger.info("{},{},{}", before, driveId, results.get(driveId).getTmpPower());
					}

					sumNumOfData += numOfData;
					long after = System.currentTimeMillis();

					// 遅延解消
					try {
						// メモリ内データがなくなるのを防ぐために1秒は必ず遅れる
						if (res.getNumOfData() < sumNumOfData + numOfData) {
							Thread.sleep(takeInterval);
						} else {
							Thread.sleep(takeInterval - (after - before));
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	public void stop() {
		command(Command.STOP);

		synchronized(lock) {
			isConnecting = false;
		}
		
		Calendar cal = Calendar.getInstance();
		endTime = cal.getTime();

		try {
			if (socket != null) {
				socket.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		is = null;
		isr = null;
		br = null;
		os = null;
		socket = null;
	}

	public Date getStartTime() {
		return startTime;
	}

	public Date getEndTime() {
		return endTime;
	}

	public int[] getDriveIds() {
		int[] driveIds = new int[results.size()];
		for(int i = 0; i < driveIds.length; i++) {
			driveIds[i] = i;
		}
		
		return driveIds;
	}

	public double getDrivePower(int driveId) {
		return results.get(driveId).getTotalPower();
	}

	public synchronized double getTotalPower() {
		double totalPower = 0.0;
//		for (ArrayList<Double> unitPowers : power) {
//			for (Double unitPower : unitPowers) {
//				totalPower += unitPower;
//			}
//		}
		for(DrivePowerResult result : results) {
			totalPower += result.getTotalPower();
		}
		
		return totalPower;
	}

	private void startConnection() {
		try {
			socket = new Socket(hostname, port);
			socket.setSoTimeout((int) takeInterval + 1000);

			is = socket.getInputStream();
			isr = new InputStreamReader(is);
			br = new BufferedReader(isr);
			bis = new BufferedInputStream(is);

			// ソケットオープン時の応答処理
			is = socket.getInputStream();
			isr = new InputStreamReader(is);
			br = new BufferedReader(isr);

			// MemoryHiLoggerからの応答を受け取る
			// 受け取ったデータ自体は特に使わない
			while (is.available() == 0)
				;
			char[] tmp = new char[is.available()];
			br.read(tmp);

			os = socket.getOutputStream();

			// データの取得間隔を設定
			if (measurementInterval == 10L) {
				command(Command.SAMP_10ms);
			} else if (measurementInterval == 50L) {
				command(Command.SAMP_50ms);
			} else {
				command(Command.SAMP_100ms);
			}

			// 測定開始
			command(Command.START);

			// 状態が変化するのを待つ
			Thread.sleep(1000);	// TODO sleepもwaitStateChangeの中で行う
			waitStateChange(65); // TODO magic number

			// システムトリガー
			command(Command.SYSTRIGGER);
			Thread.sleep(1000);	// TODO sleepもwaitStateChangeの中で行う
			waitStateChange(35); // TODO magic number

			// データ要求
			// TODO 電圧データの取得方法を検討
			command(Command.REQUIRE_DATA);
			while (is.available() == 0)
				;
			byte[] rawData = new byte[is.available()];
			dataLength = rawData.length;
			bis.read(rawData);

			Thread.sleep(takeInterval);

			// 1回のデータ取得数を設定
			byte num = (byte) (takeInterval / measurementInterval);
			Command.setRequireNumOfData(num);

			isConnecting = true;
		} catch (UnknownHostException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (InterruptedException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	// メモリハイロガーの状態が引数で与えられた状態に遷移するまで待つ
	private void waitStateChange(int expectedState) {
		byte state = (byte) 0xff;
		while (state != expectedState) {
			byte[] rawData = command(Command.REQUIRE_STATE);
			Response res = new Response(rawData);
			state = res.getState();
		}
	}

	// コマンドを発行
	private byte[] command(byte[] cmd) {
		sendCommand(cmd);
		return getCommand();
	}

	// MemoryHiLoggerにコマンドを送信
	private void sendCommand(byte[] cmd) {
		try {
			os.write(cmd);
			os.flush();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	// MemoryHiLoggerからコマンドを受信
	private byte[] getCommand() {
		try {
			while (is.available() == 0)
				;
			byte[] record = new byte[is.available()];
			is.read(record);
			return record;
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
			return null;
		}
	}

	// MemoryHiLoggerから電圧データを受け取り消費電力を計算
	private void getData() {
		byte[] rawData = new byte[dataLength];
		try {
			bis.read(rawData);
//			getVolt(raw);
//			getPower();
			calcPower(new Response(rawData));
			Command.incRequireDataCommand();
		} catch (Exception e) {
			e.printStackTrace();
			stop();
		}
	}

//	// 生データから電圧リストを取得
//	private void getVolt(byte[] rec) {
//		String raw = "";
//		int index = 21;
//
//		if (rec[0] == 0x01 && rec[1] == 0x00 && rec[2] == 0x01) { // データ転送コマンド
//			for (int unit = 1; unit < 9; unit++) {
//				for (int ch = 1; ch < 17; ch++) {
//					for (int i = 0; i < 4; i++) { // 個々の電圧
//						if (ch <= MAX_CH && unit <= MAX_UNIT) {
//							raw += String.format("%02x", rec[index]);
//						}
//						index++;
//					}
//					if (ch <= MAX_CH && unit <= MAX_UNIT) {
//						// 電圧値に変換(スライドp47)
//						// 電圧軸レンジ
//						// 資料： 1(V/10DIV)
//						// ロガーユーティリティ： 100 mv f.s. -> 0.1(V/10DIV)???
//						volt.get(unit - 1)
//								.add(((double) Integer.parseInt(raw, 16) - 32768.0) * 0.1 / 20000.0);
//					}
//					raw = "";
//				}
//			}
//		} else { // データ転送コマンドでない場合
//			System.out.println("NULL");	// TODO exceptionにする
//			volt = null;
//		}
//	}

	private void calcPower(Response res) {
		int driveId = 0;
		
		for(int unit = 1; unit < MAX_UNIT + 1; unit++) {
			for(int ch = 1; ch < MAX_CH + 1; ch++) {
				double volt5 = res.getVolt(unit, ch);
				double volt12 = res.getVolt(unit, ch + 1);
				double power = Math.abs(volt5 * 50 + volt12 * 120);
				
				DrivePowerResult dpr = results.get(driveId);
				dpr.setPower(power);
				
				ch++;
				driveId++;
			}
		}
	}
	
//	// 電圧リストから消費電力リストを取得
//	private void getPower() {
//		for (int unit = 0; unit < MAX_UNIT; unit++) {
//			int voltListSize = volt.get(unit).size();
//
//			if (voltListSize % 2 != 0) {
//				voltListSize--;
//			}
//			for (int i = 0; i < voltListSize; i += 2) {
//				// TODO どっちのチャンネルが12Vか5Vかを判別できるようにする必要がある
//				// ch1が赤5V線、ch2が黄12V線
//				power.get(unit).add(
//						Math.abs((Double) volt.get(unit).get(i)) * 50.0
//								+ Math.abs((Double) volt.get(unit).get(i + 1))
//								* 120.0);
//			}
//			volt.get(unit).clear();
//		}
//	}
}
