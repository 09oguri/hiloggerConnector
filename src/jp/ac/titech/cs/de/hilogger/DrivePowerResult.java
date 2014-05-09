package jp.ac.titech.cs.de.hilogger;

public class DrivePowerResult {
	private double totalPower;
	private double tmpPower;
	
	public DrivePowerResult() {
		this.totalPower = 0.0;
		this.tmpPower = 0.0;
	}
	
	public double getTotalPower() {
		return totalPower;
	}
	
	public double getTmpPower() {
		return tmpPower;
	}
	
	public void setPower(double tmpPower) {
		this.tmpPower = tmpPower;
		this.totalPower += tmpPower;
	}
}
