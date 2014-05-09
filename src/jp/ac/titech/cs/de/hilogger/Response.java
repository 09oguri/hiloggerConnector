package jp.ac.titech.cs.de.hilogger;

public class Response {
	private final byte[] rawData;

	public Response(byte[] rawData) {
		this.rawData = rawData;
	}

	/**
	 * メモリーハイロガーの状態を取得する
	 * 
	 * @return 状態番号
	 */
	public byte getState() {
		if (rawData[0] == 0x02 && rawData[1] == 0x01) {
			switch (rawData[2]) {
			case 0x50: // スタートコマンド
			case 0x51: // ストップコマンド
			case 0x57: // 測定状態要求コマンド
			case 0x58: // アプリシステムトリガコマンド
				return rawData[5];
			}
		}
		return (byte) 0xff;
	}

	/**
	 * メモリ内のデータ数を取得する
	 * 
	 * @return メモリ内データ数
	 */
	public long getNumOfData() {
		// データ転送コマンドの仕様
		final int dataNumStart = 13;
		final int dataNumSize = 8;

		// データ転送コマンドかどうか確認
		if (rawData[0] == 0x02 && rawData[1] == 0x01 && rawData[2] == 0x55) {
			String tmp = "";
			for (int i = dataNumStart; i < dataNumStart + dataNumSize; i++) {
				tmp += String.format("%02x", rawData[i]);
			}
			return Long.parseLong(tmp, 16);
		}
		return -1;
	}

	/**
	 * 引数で指定したユニットのチャンネルの電圧値を取得する
	 * 
	 * @param unit
	 *            ユニット番号（1オリジン）
	 * @param ch
	 *            チャンネル番号（1オリジン）
	 * @return 電圧値[v]
	 */
	public double getVolt(int unit, int ch) {
		// メモリーハイロガーの仕様
		final int maxUnitNum = 8;
		final int maxChNum = 15;

		// データ転送コマンドの仕様
		final int headerSize = 21;
		final int uDataSize = 64;
		final int chDataSize = 4;

		// 測定電圧[v]
		double volt = 0.0;

		if (unit < 1 || ch < 1 || unit > maxUnitNum || ch > maxChNum) {
			throw new IllegalArgumentException("ユニットは1から8、チャンネルは1から15");
		}

		// 引数で指定されたユニットとチャンネルの電圧データ開始位置
		int index = headerSize + (unit - 1) * uDataSize + (ch - 1) * chDataSize;

		// データ転送コマンドかどうか確認
		if (rawData[0] == 0x01 && rawData[1] == 0x00 && rawData[2] == 0x01) {
			String tmp = "";
			for (int i = index; i < chDataSize; i++) {
				tmp += String.format("%02x", rawData[i]);
			}
			// TODO 変換が正しいか確認
			// 電圧値に変換(資料p47)
			// 電圧軸レンジ： 1(V/10DIV)
			// ロガーユーティリティ： 100 mv f.s. -> 0.1(V/10DIV) としている
			volt = ((double) Integer.parseInt(tmp, 16) - 32768.0) * 0.1 / 20000.0;
		}

		return volt;
	}
}
